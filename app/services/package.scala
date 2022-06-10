import actors.{Register, Registered, Start, Started, Stop, Stopped}
import akka.actor.{ActorRef, ActorSystem}
import akka.util.Timeout
import dao.{Webhook, WebhookDao}
import akka.pattern.ask
import akka.util.Timeout
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

package object services {

  trait HooksManagerService[X, Y] {
    def init(): Future[Seq[Started[X]]]
    def start(key: Y): Future[Started[X]]
    def stop(key: Y): Future[Stopped[X]]
    def register(hook: Webhook): Future[Registered[X]]
  }

  trait HooksManager[X, Y] {
    val webhookDao: WebhookDao
    val actor: ActorRef
    implicit val system: ActorSystem
    implicit val executionContext: ExecutionContext

    def sendAndReceive[T, R](message: T): Future[R] = {
      (actor ? message) map {
        case Success(x: R) => x
        case Failure(ex) => throw ex
      }
    }

    def start(uri: X): Future[Started[Y]] = sendAndReceive(Start(uri))
    def stop(uri: X): Future[Stopped[Y]] = sendAndReceive(Stop(uri))
    def register(hook: Y): Future[Registered[Y]] = sendAndReceive(Register(hook))
    implicit val timeout: Timeout = 1.minute
  }
}
