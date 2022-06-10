import actors.{Register, Registered, Start, Started, Stop, Stopped}
import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import dao.{HookDao, Webhook, WebhookDao}
import akka.pattern.ask
import akka.util.Timeout
import org.slf4j.LoggerFactory

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

package object services {

  trait HooksManagerService[X, Y] {
    def init(): Future[Seq[Started[X]]]
    def start(key: Y): Future[Started[X]]
    def stop(key: Y): Future[Stopped[X]]
    def register(hook: X): Future[Registered[X]]
  }

  trait HooksManager[X, Y] {

    private val logger = LoggerFactory.getLogger(classOf[HooksManager[X, Y]])

    val hookDao: HookDao[X, Y]
    val actor: ActorRef

    implicit val system: ActorSystem
    implicit val executionContext: ExecutionContext

    implicit val timeout: Timeout = 1.minute

    def init(): Future[Seq[Started[Y]]] = {

      val initFuture = for {
        _ <- hookDao.init()
        keys <- hookDao.allKeys()
        started <- Future.sequence(keys.map(key => start(key)))
      } yield started

      initFuture.onComplete {
        case Success(x) => logger.info(f"Started ${x.size} hooks.")
        case Failure(exception) => logger.error(f"Failed to load hooks: ${exception.getMessage}")
      }

      initFuture
    }

    def sendAndReceive[T, R](message: T): Future[R] = {
      (actor ? message) map {
        case Success(x: R) => x
        case Failure(ex) => throw ex
      }
    }

    def start(key: X): Future[Started[Y]] = sendAndReceive(Start(key))
    def stop(key: X): Future[Stopped[Y]] = sendAndReceive(Stop(key))
    def register(hook: Y): Future[Registered[Y]] = sendAndReceive(Register(hook))
  }
}
