import io.chrisdavenport.rediculous._
import cats.implicits._
import cats.effect._
import fs2.io.net._
import com.comcast.ip4s._

// Send a Single Transaction to the Redis Server
object TransactionExample extends IOApp {

  def run(args: List[String]): IO[ExitCode] = {
    val r = for {
      // maxQueued: How many elements before new submissions semantically block. Tradeoff of memory to queue jobs. 
      // Default 1000 is good for small servers. But can easily take 100,000.
      // workers: How many threads will process pipelined messages.
      connection <- RedisConnection.queued[IO](Network[IO], host"localhost", port"6379", maxQueued = 10000, workers = 2)
    } yield connection

    r.use {client =>
      val r = (
        RedisCommands.ping[RedisTransaction],
        RedisCommands.del[RedisTransaction]("foo"),
        RedisCommands.get[RedisTransaction]("foo"),
        RedisCommands.set[RedisTransaction]("foo", "value"),
        RedisCommands.get[RedisTransaction]("foo")
      ).tupled

      val multi = r.transact[IO]

      multi.run(client).flatTap(output => IO(println(output)))

    }.as(ExitCode.Success)

  }
}