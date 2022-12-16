package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import actors.RateLimitingBatchingActor.TxBatch
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import play.api.Logging

import java.time.temporal.ChronoUnit
import java.time.{Clock, Instant}
import scala.annotation.unused
import scala.concurrent.duration.{Duration, DurationInt}

object RateLimitingBatchingActor {

  final case class TxBatch(messages: Seq[TxUpdate])

  trait Factory {
    def apply(@unused out: ActorRef, clock: Clock): Actor
  }

  def props(out: ActorRef, clock: Clock)(implicit system: ActorSystem): Props =
    Props(new RateLimitingBatchingActor(out)(clock))
}

class RateLimitingBatchingActor @Inject() (@Assisted val out: ActorRef)(
    clock: Clock
) extends Actor
    with Logging
    with UnrecognizedMessageHandlerFatal {

  val minInterval: Duration = 2.seconds

  override def receive: Receive = slow(clock.instant())

  def fast(batch: Vector[TxUpdate], previous: Instant): Receive = {
    case tx: TxUpdate =>
      val now = clock.instant()
      logger.debug(s"Fast mode: batching up $tx at $now")
      val newBatch = batch :+ tx
      if (timeDeltaNanos(now, previous) > minInterval.toNanos) {
        logger.debug(s"Switching to slow mode and sending $newBatch")
        out ! TxBatch(newBatch)
        context.become(slow(now))
      } else {
        context.become(fast(newBatch, now))
      }
    case x =>
      unrecognizedMessage(x)
  }

  def slow(previous: Instant): Receive = {
    case tx: TxUpdate =>
      val now = clock.instant()
      logger.debug(s"Slow mode: sending $tx at $now")
      out ! TxBatch(Vector(tx))
      if (timeDeltaNanos(now, previous) <= minInterval.toNanos) {
        logger.debug("Switching to fast mode")
        context.become(fast(Vector(), now))
      } else {
        context.become(slow(now))
      }
    case x =>
      unrecognizedMessage(x)
  }

  private def timeDeltaNanos(now: Instant, previous: Instant): Long =
    Math.abs(ChronoUnit.NANOS.between(now, previous))
}
