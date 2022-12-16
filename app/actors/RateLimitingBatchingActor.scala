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
    def apply(@unused out: ActorRef, clock: Clock): Actor
  }

  def props(out: ActorRef, clock: Clock)(implicit system: ActorSystem): Props =
    Props(new RateLimitingBatchingActor(out)(clock))
}

class RateLimitingBatchingActor @Inject() (@Assisted val out: ActorRef)(
    clock: Clock
) extends Actor
    with Logging {

  val minInterval: Duration = 1.seconds

  override def receive: Receive = slow(clock.instant())

  def fast(batch: Vector[TxUpdate], previous: Instant): Receive = {
    case tx: TxUpdate =>
      val now = clock.instant()
      val newBatch = batch :+ tx
      if (timeDeltaNanos(now, previous) > minInterval.toNanos) {
        out ! TxBatch(newBatch)
        context.become(slow(now))
      } else {
        context.become(fast(newBatch, now))
      }
  }

  def slow(previous: Instant): Receive = { case tx: TxUpdate =>
    val now = clock.instant()
    out ! TxBatch(Vector(tx))
    if (timeDeltaNanos(now, previous) <= minInterval.toNanos) {
      context.become(fast(Vector(), now))
    } else {
      context.become(slow(now))
    }
  }

  private def timeDeltaNanos(now: Instant, previous: Instant): Long =
    Math.abs(ChronoUnit.NANOS.between(now, previous))
}
