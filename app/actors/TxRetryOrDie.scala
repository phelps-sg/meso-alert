package actors
import akka.actor._
import scala.concurrent.Future
import play.api.Logging
import scala.util.{Failure, Success}
import actors.AuthenticationActor.Die


trait TxRetryOrDie[T] extends Actor with Logging {
  val maxRetryCount: Int
  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global

//  message types
  case class Retry(tx: TxUpdate, exception: Throwable)

  def process(tx: TxUpdate) : Future[T]

  def retryOrDie(currentRetryCount: Int): Receive = {
    case Retry(tx, _) if currentRetryCount < maxRetryCount =>
      logger.error(s"Error processing tx ${tx.hash}. Retrying...")
      process(tx) onComplete {
        case Success(_) =>
          logger.debug("Retry successfull.")
          context.become(receive)
        case Failure(ex) =>
          context.become(retryOrDie(currentRetryCount + 1))
          self ! Retry(tx, ex)
      }
    case Retry(tx, ex) if currentRetryCount >= maxRetryCount =>
      context.become(receive)
      self ! Die(s"Could not proccess tx ${tx.hash}. $ex")
  }

}