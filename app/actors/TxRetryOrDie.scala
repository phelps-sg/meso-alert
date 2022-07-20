package actors
import actors.AuthenticationActor.Die
import akka.actor._
import play.api.Logging
import scala.concurrent.duration._
import scala.math.{min, pow}
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps


object TxRetryOrDie {
  //  message types
  case class Retry(tx: TxUpdate, retryCount: Int, exception: Option[Exception])
  case class ScheduleRetry(timeout: FiniteDuration, tx: TxUpdate, retryCount: Int, exception: Option[Exception])
}

trait TxRetryOrDie[T] extends Actor with Timers with Logging {
  import TxRetryOrDie._
  val random: scala.util.Random
  val maxRetryCount: Int
  implicit val ec: ExecutionContext
//  back-off policy configuration (milliseconds)
  val base = 500
  val cap = 10000

  def process(tx: TxUpdate) : Future[T]
  def success(): Unit
  def failure(ex: Throwable): Unit = logger.error(s"Failed to process tx, ${ex.getMessage}.")
  def actorDeath(reason: String): Unit = logger.info(s"${this.getClass.getName} terminating because $reason")

  def calculateWaitTime(retryCount: Int): FiniteDuration = {
    random.between(0, min(cap, base * pow(2, retryCount))) milliseconds
  }

  def receive : Receive = {

    case tx: TxUpdate => self ! Retry(tx, 0, None)

    case Retry(tx, retryCount, _) if retryCount < maxRetryCount =>
      process(tx) map {
        _ => success()
      } recover {
        case ex: Exception =>
          failure(ex)
          self ! ScheduleRetry(calculateWaitTime(retryCount), tx, retryCount + 1, Some(ex))
        }

    case Retry(tx, retryCount, ex) if retryCount >= maxRetryCount =>
      self ! Die(s"Could not process tx ${tx.hash}. ${ex.get.getMessage}")

    case ScheduleRetry(timeout, tx, retryCount, ex) =>
      timers.startSingleTimer("retry", Retry(tx, retryCount, ex), timeout)

    case Die(reason)  =>
      actorDeath(reason)
      self ! PoisonPill
  }
}
