package actors
import actors.AuthenticationActor.Die
import actors.MessageHandlers.UnrecognizedMessageHandler
import akka.actor._
import play.api.Logging

import scala.concurrent.duration._
import scala.concurrent.{ExecutionContext, Future}
import scala.language.postfixOps
import scala.math.{min, pow}
import scala.reflect.ClassTag

object RetryOrDieActor {
  //  message types
  case class Retry[M](tx: M, retryCount: Int, exception: Option[Exception])
  case class ScheduleRetry[M](
      timeout: FiniteDuration,
      tx: M,
      retryCount: Int,
      exception: Option[Exception]
  )
}

/** Abstract superclass for actors which attempt to process a message in the
  * future and recover from ephemeral errors by retrying a specified number of
  * times before terminating.
  * @tparam T
  *   The type of result returned in the future by the retryable operation
  * @tparam M
  *   The type of the incoming message which represents an operation that can be
  *   retried
  */
abstract class RetryOrDieActor[T, M: ClassTag]
    extends Actor
    with Timers
    with Logging
    with UnrecognizedMessageHandler {

  import RetryOrDieActor._

  val random: scala.util.Random
  val maxRetryCount: Int
  implicit val ec: ExecutionContext

  val backoffPolicyBase: FiniteDuration = 500 milliseconds
  val backoffPolicyCap: FiniteDuration = 10 seconds
  val backoffPolicyMin: FiniteDuration = 0 milliseconds

  protected def process(tx: M): Future[T]

  protected def success(tx: M): Unit =
    logger.debug(s"Successfully processed $tx.")
  protected def failure(ex: Throwable): Unit =
    logger.error(s"Failed to process tx: ${ex.getMessage}.")
  protected def actorDeath(reason: String): Unit =
    logger.error(s"$self terminating because $reason")

  protected def calculateWaitTime(retryCount: Int): FiniteDuration = {
    def nanoseconds(duration: FiniteDuration) = duration.toNanos.toDouble
    val minimum = nanoseconds(backoffPolicyMin)
    val cap = nanoseconds(backoffPolicyCap)
    val base = nanoseconds(backoffPolicyBase)
    random
      .between(
        minimum,
        minimum + min(cap - minimum, base * pow(2, retryCount))
      ) nanoseconds
  }

  protected def triggerRetry(msg: ScheduleRetry[M]): Unit = self ! msg

  override def receive: Receive = {

    case tx: M => self ! Retry(tx, 0, None)

    case Retry(tx: M, retryCount, _) if retryCount < maxRetryCount =>
      process(tx) map { _ =>
        success(tx)
      } recover { case ex: Exception =>
        logger.debug(s"$self: retryCount = $retryCount for $tx")
        failure(ex)
        val msg = ScheduleRetry(
          calculateWaitTime(retryCount),
          tx,
          retryCount + 1,
          Some(ex)
        )
        triggerRetry(msg)
      }

    case Retry(tx: M, retryCount, Some(ex)) if retryCount >= maxRetryCount =>
      self ! Die(s"Could not process tx $tx: ${ex.getMessage}")

    case ScheduleRetry(timeout, tx: M, retryCount, ex) =>
      logger.debug(s"$self: Scheduling retry in $timeout for $tx... ")
      timers.startSingleTimer(tx, Retry(tx, retryCount, ex), timeout)

    case Die(reason) =>
      actorDeath(reason)
      self ! PoisonPill

    case x =>
      unrecognizedMessage(x)
  }

}
