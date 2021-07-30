package io.chrisdavenport.rediculous

import cats.effect._
import cats.effect.implicits._
import cats._
import cats.implicits._
import cats.data._
import _root_.org.typelevel.keypool._
import cats.effect.std._
import fs2.io.net._
import fs2._
import scala.concurrent.duration._
import com.comcast.ip4s._
import _root_.io.chrisdavenport.rediculous.cluster.HashSlot
import _root_.io.chrisdavenport.rediculous.cluster.ClusterCommands
import fs2.io.net.tls.TLSContext
import fs2.io.net.tls.TLSParameters
import java.time.Instant
import _root_.io.chrisdavenport.rediculous.cluster.ClusterCommands.ClusterSlots

sealed trait RedisConnection[F[_]]
object RedisConnection{
  private case class Queued[F[_]](queue: Queue[F, Chunk[(Deferred[F, Either[Throwable, Resp]], Resp)]], usePool: Resource[F, Managed[F, Socket[F]]]) extends RedisConnection[F]
  private case class PooledConnection[F[_]](
    pool: KeyPool[F, Unit, (Socket[F], F[Unit])]
  ) extends RedisConnection[F]
  private case class DirectConnection[F[_]](socket: Socket[F]) extends RedisConnection[F]

  private case class Cluster[F[_]](queue: Queue[F, Chunk[(Deferred[F, Either[Throwable, Resp]], Option[String], Option[(Host, Port)], Int, Resp)]]) extends RedisConnection[F]

  // Guarantees With Socket That Each Call Receives a Response
  // Chunk must be non-empty but to do so incurs a penalty
  private[rediculous] def explicitPipelineRequest[F[_]: MonadThrow](socket: Socket[F], calls: Chunk[Resp], maxBytes: Int = 8 * 1024 * 1024, timeout: Option[FiniteDuration] = 5.seconds.some): F[List[Resp]] = {
    def getTillEqualSize(acc: List[List[Resp]], lastArr: Array[Byte]): F[List[Resp]] = 
    socket.read(maxBytes).flatMap{
      case None => 
        ApplicativeError[F, Throwable].raiseError[List[Resp]](RedisError.Generic("Rediculous: Terminated Before reaching Equal size"))
      case Some(bytes) => 
        Resp.parseAll(lastArr.toArray ++ bytes.toIterable) match {
          case e@Resp.ParseError(_, _) => ApplicativeError[F, Throwable].raiseError[List[Resp]](e)
          case Resp.ParseIncomplete(arr) => getTillEqualSize(acc, arr)
          case Resp.ParseComplete(value, rest) => 
            if (value.size + acc.foldMap(_.size) === calls.size) (value ::acc ).reverse.flatten.pure[F]
            else getTillEqualSize(value :: acc, rest)
          
        }
    }
    if (calls.nonEmpty){
      val arrayB = new scala.collection.mutable.ArrayBuffer[Byte]
        calls.toList.foreach{
          case resp => 
            arrayB.++=(Resp.encode(resp))
        }
      socket.write(Chunk.array(arrayB.toArray)) >>
      getTillEqualSize(List.empty, Array.emptyByteArray)
    } else Applicative[F].pure(List.empty)
  }

  def runRequestInternal[F[_]: Async](connection: RedisConnection[F])(
    inputs: NonEmptyList[NonEmptyList[String]],
    key: Option[String]
  ): F[F[NonEmptyList[Resp]]] = {
      val chunk = Chunk.seq(inputs.toList.map(Resp.renderRequest))
      def withSocket(socket: Socket[F]): F[NonEmptyList[Resp]] = explicitPipelineRequest[F](socket, chunk).flatMap(l => Sync[F].delay(l.toNel.getOrElse(throw RedisError.Generic("Rediculous: Impossible Return List was Empty but we guarantee output matches input"))))
      connection match {
      case PooledConnection(pool) => Functor[({type M[A] = KeyPool[F, Unit, A]})#M].map(pool)(_._1).take(()).use{
        m => withSocket(m.value).attempt.flatTap{
          case Left(_) => m.canBeReused.set(Reusable.DontReuse)
          case _ => Applicative[F].unit
        }
      }.rethrow.map(_.pure[F])
      case DirectConnection(socket) => withSocket(socket).map(_.pure[F])
      case Queued(queue, _) => chunk.traverse(resp => Deferred[F, Either[Throwable, Resp]].map((_, resp))).flatMap{ c => 
        queue.offer(c).as {
          MonadThrow[F].rethrow(
            c.traverse(_._1.get).flatMap(_.sequence.traverse(l => Sync[F].delay(l.toNel.getOrElse(throw RedisError.Generic("Rediculous: Impossible Return List was Empty but we guarantee output matches input")))))
          )
        }   
      }
      case Cluster(queue) => chunk.traverse(resp => Deferred[F, Either[Throwable, Resp]].map((_, key, None, 0, resp))).flatMap{ c => 
        queue.offer(c).as {
          c.traverse(_._1.get).flatMap(_.sequence.traverse(l => Sync[F].delay(l.toNel.getOrElse(throw RedisError.Generic("Rediculous: Impossible Return List was Empty but we guarantee output matches input"))))).rethrow
        }
      }   
    }
  }

  // Can Be used to implement any low level protocols.
  def runRequest[F[_]: Async, A: RedisResult](connection: RedisConnection[F])(input: NonEmptyList[String], key: Option[String]): F[F[Either[Resp, A]]] = 
    runRequestInternal(connection)(NonEmptyList.of(input), key).map(_.map(nel => RedisResult[A].decode(nel.head)))

  def runRequestTotal[F[_]: Async, A: RedisResult](input: NonEmptyList[String], key: Option[String]): Redis[F, A] = Redis(Kleisli{(connection: RedisConnection[F]) => 
    runRequest(connection)(input, key).map{ fE => 
      fE.flatMap{
        case Right(a) => a.pure[F]
        case Left(e@Resp.Error(_)) => ApplicativeError[F, Throwable].raiseError[A](e)
        case Left(other) => ApplicativeError[F, Throwable].raiseError[A](RedisError.Generic(s"Rediculous: Incompatible Return Type for Operation: ${input.head}, got: $other"))
      }
    }
  })

  private[rediculous] def closeReturn[F[_]: MonadThrow, A](fE: F[Either[Resp, A]]): F[A] = 
    fE.flatMap{
        case Right(a) => a.pure[F]
        case Left(e@Resp.Error(_)) => ApplicativeError[F, Throwable].raiseError[A](e)
        case Left(other) => ApplicativeError[F, Throwable].raiseError[A](RedisError.Generic(s"Rediculous: Incompatible Return Type: Got $other"))
      }

  def single[F[_]: Async](
    sg: SocketGroup[F],
    host: Host,
    port: Port,
    tlsContext: Option[TLSContext[F]] = None,
    tlsParameters: TLSParameters = TLSParameters.Default
  ): Resource[F, RedisConnection[F]] = 
    for {
      socket <- sg.client(SocketAddress(host,port), Nil)
      out <- elevateSocket(socket, tlsContext, tlsParameters)
    } yield RedisConnection.DirectConnection(out)

  def pool[F[_]: Async](
    sg: SocketGroup[F],
    host: Host,
    port: Port,
    tlsContext: Option[TLSContext[F]] = None,
    tlsParameters: TLSParameters = TLSParameters.Default
  ): Resource[F, RedisConnection[F]] = 
    KeyPoolBuilder[F, Unit, (Socket[F], F[Unit])](
      {_ => sg.client(SocketAddress(host,port), Nil)
        .flatMap(elevateSocket(_, tlsContext, tlsParameters)).allocated
      },
      { case (_, shutdown) => shutdown}
    ).build.map(PooledConnection[F](_))

  // Only allows 1k queued actions, before new actions block to be accepted.
  def queued[F[_]: Async](
    sg: SocketGroup[F],
    host: Host,
    port: Port,
    maxQueued: Int = 10000,
    workers: Int = 2,
    tlsContext: Option[TLSContext[F]] = None,
    tlsParameters: TLSParameters = TLSParameters.Default
  ): Resource[F, RedisConnection[F]] = 
    for {
      queue <- Resource.eval(Queue.bounded[F, Chunk[(Deferred[F, Either[Throwable,Resp]], Resp)]](maxQueued))
      keypool <- KeyPoolBuilder[F, Unit, (Socket[F], F[Unit])](
        {_ => sg.client(SocketAddress(host,port), Nil)
          .flatMap(elevateSocket(_, tlsContext, tlsParameters))
          .allocated
        },
        { case (_, shutdown) => shutdown}
      ).build
      _ <- 
          Stream.fromQueueUnterminatedChunk(queue).chunks.map{chunk =>
            val s = if (chunk.nonEmpty) {
                Stream.eval(
                  Functor[({type M[A] = KeyPool[F, Unit, A]})#M].map(keypool)(_._1).take(()).use{m =>
                  val out = chunk.map(_._2)
                  explicitPipelineRequest(m.value, out).attempt.flatTap{// Currently Guarantee Chunk.size === returnSize
                    case Left(_) => m.canBeReused.set(Reusable.DontReuse)
                    case _ => Applicative[F].unit
                  }
                }.flatMap{
                  case Right(n) => 
                    n.zipWithIndex.traverse_{
                      case (ref, i) => 
                        val (toSet, _) = chunk(i)
                        toSet.complete(Either.right(ref))
                    }
                  case e@Left(_) => 
                    chunk.traverse_{ case (deff, _) => deff.complete(e.asInstanceOf[Either[Throwable, Resp]])}
                }) 
            } else {
              Stream.empty
            }
            s ++ Stream.exec(Concurrent[F].cede)
          }.parJoin(workers) // Worker Threads
          .compile
          .drain
          .background
    } yield Queued(queue, keypool.take(()).map(Functor[({type M[A] = Managed[F, A]})#M].map(_)(_._1)))

  def cluster[F[_]: Async](
    sg: SocketGroup[F],
    host: Host,
    port: Port,
    maxQueued: Int = 10000,
    workers: Int = 2,
    parallelServerCalls: Int = Int.MaxValue,
    tlsContext: Option[TLSContext[F]] = None,
    tlsParameters: TLSParameters = TLSParameters.Default,
    useDynamicRefreshSource: Boolean = true, // Set to false to only use initially provided host for topology refresh
    cacheTopologySeconds: FiniteDuration = 1.second, // How long topology will not be rechecked for after a succesful refresh
  ): Resource[F, RedisConnection[F]] = 
    for {
      keypool <- KeyPoolBuilder[F, (Host, Port), (Socket[F], F[Unit])](
        {(t: (Host, Port)) => sg.client(SocketAddress(host,port), Nil)
            .flatMap(elevateSocket(_, tlsContext, tlsParameters))
            .allocated
        },
        { case (_, shutdown) => shutdown}
      ).build

      // Cluster Topology Acquisition and Management
      sockets <- Resource.eval(keypool.take((host, port)).map(_.value._1).map(DirectConnection(_)).use(ClusterCommands.clusterslots[({ type M[A] = Redis[F, A] })#M].run(_)))
      now <- Resource.eval(Temporal[F].realTime.map(_.toMillis))
      refreshLock <- Resource.eval(Semaphore[F](1L))
      refTopology <- Resource.eval(Ref[F].of((sockets, now)))
      refreshTopology = refreshLock.permit.use(_ =>
        (
          refTopology.get
            .flatMap{ case (topo, setAt) => 
              if (useDynamicRefreshSource) 
                Applicative[F].pure((NonEmptyList((host, port), topo.l.flatMap(c => c.replicas).map(r => (r.host, r.port))), setAt))
              else 
                Applicative[F].pure((NonEmptyList.of((host, port)), setAt))
          },
          Temporal[F].realTime.map(_.toMillis)
        ).tupled
        .flatMap{
          case ((_, setAt), now) if setAt >= (now - cacheTopologySeconds.toMillis) => Applicative[F].unit
          case ((l, _), _) => 
            val nelActions: NonEmptyList[F[ClusterSlots]] = l.map{ case (host, port) => 
              keypool.take((host, port)).map(_.value._1).map(DirectConnection(_)).use(ClusterCommands.clusterslots[({ type M[A] = Redis[F, A] })#M].run(_))
            }
            raceNThrowFirst(nelActions)
              .flatMap(s => Clock[F].realTime.map(_.toMillis).flatMap(now => refTopology.set((s,now))))
        }
      )
      queue <- Resource.eval(Queue.bounded[F, Chunk[(Deferred[F, Either[Throwable,Resp]], Option[String], Option[(Host, Port)], Int, Resp)]](maxQueued))
      cluster = Cluster(queue)
      _ <- 
          Stream.fromQueueUnterminatedChunk(queue).chunks.map{chunk =>
            val s = if (chunk.nonEmpty) {
              Stream.eval(refTopology.get).map{ case (topo,_) => 
                Stream.eval(topo.random[F]).flatMap{ default => 
                Stream.emits(
                    chunk.toList.groupBy{ case (_, s, server,_,_) => // TODO Investigate Efficient Group By
                    server.orElse(s.flatMap(key => topo.served(HashSlot.find(key)))).getOrElse(default) // Explicitly Set Server, Key Hashslot Server, or a default server if none selected.
                  }.toSeq
                ).evalMap{
                  case (server, rest) => 
                    Functor[({type M[A] = KeyPool[F, (Host, Port), A]})#M].map(keypool)(_._1).take(server).use{m =>
                      val out = Chunk.seq(rest.map(_._5))
                      explicitPipelineRequest(m.value, out).attempt.flatTap{// Currently Guarantee Chunk.size === returnSize
                        case Left(_) => m.canBeReused.set(Reusable.DontReuse)
                        case _ => Applicative[F].unit
                      }
                    }.flatMap{
                    case Right(n) => 
                      n.zipWithIndex.traverse_{
                        case (ref, i) => 
                          val (toSet, key, _, retries, initialCommand) = rest(i)
                          ref match {
                            case e@Resp.Error(s) if (s.startsWith("MOVED") && retries <= 5)  => // MOVED 1234-2020 127.0.0.1:6381
                              refreshTopology.attempt.void >>
                              // Offer To Have it reprocessed. 
                              // If the queue is full return the error to the user
                              cluster.queue.tryOffer(Chunk.singleton((toSet, key, extractServer(s), retries + 1, initialCommand)))
                                .ifM( 
                                  Applicative[F].unit,
                                  toSet.complete(Either.right(e)).void
                                )
                            case e@Resp.Error(s) if (s.startsWith("ASK") && retries <= 5) => // ASK 1234-2020 127.0.0.1:6381
                              val serverRedirect = extractServer(s)
                              serverRedirect match {
                                case s@Some(_) => // This is a Special One Off, Requires a Redirect
                                  Deferred[F, Either[Throwable, Resp]].flatMap{d => // No One Cares About this Callback
                                    val asking = (d, key, s, 6, Resp.renderRequest(NonEmptyList.of("ASKING"))) // Never Repeat Asking
                                    val repeat = (toSet, key, s, retries + 1, initialCommand)
                                    val chunk = Chunk(asking, repeat)
                                    cluster.queue.tryOffer(chunk) // Offer To Have it reprocessed. 
                                      //If the queue is full return the error to the user
                                      .ifM(
                                        Applicative[F].unit,
                                        toSet.complete(Either.right(e)).void
                                      )
                                  }
                                case None => 
                                  toSet.complete(Either.right(e)).void
                              }
                            case otherwise => 
                              toSet.complete(Either.right(otherwise)).void
                          }
                      }
                    case e@Left(_) =>
                      refreshTopology.attempt.void >>
                      rest.traverse_{ case (deff, _, _, _, _) => deff.complete(e.asInstanceOf[Either[Throwable, Resp]])}
                  }

                }
              }}.parJoin(parallelServerCalls) // Send All Acquired values simultaneously. Should be mostly IO awaiting callback
            } else Stream.empty
            s ++ Stream.exec(Async[F].cede)
          }.parJoin(workers)
            .compile
            .drain
            .background
    } yield cluster

  private def elevateSocket[F[_]](socket: Socket[F], tlsContext: Option[TLSContext[F]], tlsParameters: TLSParameters): Resource[F, Socket[F]] = 
    tlsContext.fold(Resource.pure[F, Socket[F]](socket))(c => c.clientBuilder(socket).withParameters(tlsParameters).build)

  // ASK 1234-2020 127.0.0.1:6381
  // MOVED 1234-2020 127.0.0.1:6381
  private def extractServer(s: String): Option[(Host, Port)] = {
    val end = s.lastIndexOf(' ')
    val portSplit = s.lastIndexOf(':')
    if (end > 0 &&  portSplit >= end + 1){
      val host = s.substring(end + 1, portSplit)
      val port = s.substring(portSplit +1, s.length())
      for {
        h <- Host.fromString(host)
        pI <- Either.catchNonFatal(port.toInt).toOption
        port <- Port.fromInt(pI)
      } yield (h, port)
    } else None
  }

  def raceNThrowFirst[F[_]: Concurrent, A](nel: NonEmptyList[F[A]]): F[A] = 
    Stream(Stream.emits(nel.toList).evalMap(identity)).covary[F].parJoinUnbounded.take(1).compile.lastOrError
}