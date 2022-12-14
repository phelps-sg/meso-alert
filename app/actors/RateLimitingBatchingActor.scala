package actors

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
    def apply(@unused out: ActorRef): Actor
  }

  def props(out: ActorRef, clock: Clock)(implicit system: ActorSystem): Props =
    Props(new RateLimitingBatchingActor(out)(clock))
}

class RateLimitingBatchingActor @Inject() (@Assisted val out: ActorRef)(
    clock: Clock
) extends Actor
    with Logging {

  val minInterval: Duration = 1.seconds

  override def receive: Receive = receiveSlow(clock.instant())

  def receiveFast(
      batchedMessages: Vector[TxUpdate],
      previous: Instant
  ): Receive = { case tx: TxUpdate =>
    val now = clock.instant()
    val newBatch = batchedMessages :+ tx
    if (timeDeltaNanos(now, previous) > minInterval.toNanos) {
      out ! TxBatch(newBatch)
      context.become(receiveSlow(now))
    } else {
      context.become(receiveFast(newBatch, now))
    }
  }

  def receiveSlow(previous: Instant): Receive = { case tx: TxUpdate =>
    val now = clock.instant()
    out ! tx
    if (timeDeltaNanos(now, previous) <= minInterval.toNanos) {
      context.become(receiveFast(Vector(), now))
    } else {
      context.become(receiveSlow(now))
    }
  }

  private def timeDeltaNanos(now: Instant, previous: Instant): Long =
    Math.abs(ChronoUnit.NANOS.between(now, previous))
}
