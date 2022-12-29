package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import actors.RateLimitingBatchingActor.{MAX_BATCH_SIZE, MIN_INTERVAL, TxBatch}
import akka.actor.{Actor, ActorRef, Props}
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import play.api.Logging

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant}
import scala.annotation.unused
import scala.concurrent.duration.{Duration, DurationInt}

object RateLimitingBatchingActor {

  val MIN_INTERVAL: Duration = 1.second
  val MAX_BATCH_SIZE: Int = 12

  final case class TxBatch(messages: Seq[TxUpdate])

  trait Factory {
    def apply(@unused out: ActorRef, clock: Clock): Actor
  }

  def props(out: ActorRef, clock: Clock): Props =
    Props(new RateLimitingBatchingActor(out)(clock))
}

/** Receives TxUpdate messages and batches them into TxBatch messages. Messages
  * received within minInterval are sent in the same batch.
  *
  * @param out
  *   The actor receiving the TxBatch messages
  * @param clock
  *   The clock used for measuring the time interval between messages
  */
class RateLimitingBatchingActor @Inject() (@Assisted val out: ActorRef)(
    clock: Clock
) extends Actor
    with Logging
    with UnrecognizedMessageHandlerFatal {

  override def receive: Receive = slow(clock.instant())

  def fast(batch: Vector[TxUpdate], previous: Instant): Receive = {
    case tx: TxUpdate =>
      val now = clock.instant()
      logger.debug(s"Fast mode: batching up $tx at $now")
      val newBatch = batch :+ tx
      if (
        timeDeltaNanos(now, previous) > MIN_INTERVAL.toNanos ||
        newBatch.size >= MAX_BATCH_SIZE
      ) {
        logger.debug(s"Switching to slow mode and sending $newBatch")
        out ! TxBatch(newBatch)
        context.become {
          slow(previous = now)
        }
      } else
        context.become {
          fast(batch = newBatch, previous = now)
        }
    case x =>
      unrecognizedMessage(x)
  }

  def slow(previous: Instant): Receive = {
    case tx: TxUpdate =>
      val now = clock.instant()
      logger.debug(s"Slow mode: sending $tx at $now")
      out ! TxBatch(Vector(tx))
      if (timeDeltaNanos(now, previous) <= MIN_INTERVAL.toNanos) {
        logger.debug("Switching to fast mode")
        context.become {
          fast(batch = Vector(), previous = now)
        }
      } else
        context.become {
          slow(previous = now)
        }
    case x =>
      unrecognizedMessage(x)
  }

  private def timeDeltaNanos(now: Instant, previous: Instant): Long =
    Math.abs(ChronoUnit.NANOS.between(now, previous))
}
