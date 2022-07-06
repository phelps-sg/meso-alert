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
  case class SwitchContext(newContext: Receive)

  def process(tx: TxUpdate) : Future[T]
  def success(): Unit
  def failure(ex: Throwable): Unit
  def actorDeath(reason: String): Unit


  def receiveDefault(currentRetryCount: Int = 0) : Receive = {
    case tx: TxUpdate => process(tx) onComplete {
      case Success(_) => success()
      case Failure(ex) =>
        failure(ex)
        self ! Retry(tx, ex)
    }

    case Retry(tx, _) if currentRetryCount < maxRetryCount =>
      logger.error(s"Error processing tx ${tx.hash}. Retrying...")
      process(tx) onComplete {
        case Success(_) =>
          success()
          self ! SwitchContext(receiveDefault())
        case Failure(ex) =>
          self ! SwitchContext(receiveDefault(currentRetryCount+1))
          self ! Retry(tx, ex)
      }
    case Retry(tx, ex) if currentRetryCount >= maxRetryCount =>
      self ! Die(s"Could not proccess tx ${tx.hash}. ${ex.getMessage}")

    case SwitchContext(newContext) => context.become(newContext)

    case Die(reason)  =>
      actorDeath(reason)
      self ! PoisonPill

  }
}