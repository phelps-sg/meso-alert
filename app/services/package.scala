import actors.{Register, Registered, Start, Started, Stop, Stopped, Update, Updated}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import dao.HookDao
import org.slf4j.Logger

import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

package object services {

  implicit val timeout: Timeout = 1.minute

  case class HooksManagerFailedException(message: String) extends Exception(message)

  trait ActorBackend {

    val actor: ActorRef
    val executionContext: ExecutionContext

    implicit val ec: ExecutionContext = executionContext

    def sendAndReceive[T, R](message: T): Future[Try[R]] = {
      (actor ? message) map { case x: Try[R] => x }
    }
  }

  trait HooksManagerService[X, Y] {
    def init(): Future[Try[Unit]]
    def start(key: X): Future[Try[Started[Y]]]
    def stop(key: X): Future[Try[Stopped[Y]]]
    def register(hook: Y): Future[Try[Registered[Y]]]
  }

  trait HooksManager[X, Y] extends ActorBackend {

    val logger: Logger

    val hookDao: HookDao[X, Y]
    val actor: ActorRef

    implicit val system: ActorSystem
    implicit val executionContext: ExecutionContext

    def init(): Future[Try[Unit]] = {

      val initFuture = for {
        _ <- hookDao.init()
        keys <- hookDao.allKeys()
        started <- Future.sequence(keys.map(key => start(key)))
      } yield started

      initFuture.onComplete {
        case Success(tries) =>
          if (tries.forall(_.isSuccess)) {
            logger.info(s"Started ${tries.size} hooks.")
          } else {
            logger.error(s"Failed to load ${tries.filter(_.isFailure)} out of ${tries.size} hooks.")
          }
        case Failure(exception) => logger.error(f"Failed to load hooks: ${exception.getMessage}.")
      }

      initFuture.map(_.forall(_.isSuccess))
        .map {
          allStarted =>
            if (allStarted)
              Success(())
            else
              Failure(HooksManagerFailedException("Some hooks failed to start up"))
        }
    }

    def start(key: X): Future[Try[Started[Y]]] = sendAndReceive(Start(key))
    def stop(key: X): Future[Try[Stopped[Y]]] = sendAndReceive(Stop(key))
    def register(hook: Y): Future[Try[Registered[Y]]] = sendAndReceive(Register(hook))
    def update(hook: Y): Future[Try[Updated[Y]]] = sendAndReceive(Update(hook))
  }
}
