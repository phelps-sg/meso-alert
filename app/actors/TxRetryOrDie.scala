package actors
import akka.actor._
import scala.concurrent.{Future, ExecutionContext}
import play.api.Logging
import actors.AuthenticationActor.Die


object TxRetryOrDie {
  //  message types
  case class Retry(tx: TxUpdate, retryCount: Int, exception: Option[Exception])

}

trait TxRetryOrDie[T] extends Actor with Logging {
  import TxRetryOrDie._
  val maxRetryCount: Int
  implicit val ec: ExecutionContext


  def process(tx: TxUpdate) : Future[T]
  def success(): Unit
  def failure(ex: Throwable): Unit = logger.error(s"Failed to process tx, ${ex.getMessage}.")
  def actorDeath(reason: String): Unit = logger.info(s"${this.getClass.getName} terminating because $reason")

  def receive : Receive = {
    case tx: TxUpdate => self ! Retry(tx, 0, None)
    case Retry(tx, retryCount, _) if retryCount < maxRetryCount =>
      process(tx) map {
        _ => success()
      } recover {
        case ex: Exception =>
          failure(ex)
          self ! Retry(tx, retryCount+1, Some(ex))
        case ex: Error =>
          logger.error(s"Fatal error: ${ex.getMessage}")
        }
    case Retry(tx, retryCount, ex) if retryCount >= maxRetryCount =>
      self ! Die(s"Could not process tx ${tx.hash}. ${ex.get.getMessage}")

    case Die(reason)  =>
      actorDeath(reason)
      self ! PoisonPill

  }
}
