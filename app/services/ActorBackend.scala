package services

import akka.actor.ActorRef
import akka.pattern.ask

import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

trait ActorBackend {

  val actor: ActorRef
  val executionContext: ExecutionContext

  implicit val ec: ExecutionContext = executionContext

  def sendAndReceive[T: ClassTag, R: ClassTag](message: T): Future[R] = {
    (actor ? message) map {
      case Success(x: R) => x
      case Failure(ex)   => throw ex
    }
  }
}
