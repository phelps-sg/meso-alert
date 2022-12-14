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

  var lastReceive: Instant = clock.instant()
  val minInterval: Duration = 1.seconds

  override def receive: Receive = receiveSlow

  def receiveFast(batchedMessages: Vector[TxUpdate]): Receive = {

    case tx: TxUpdate =>
      val now = clock.instant()
      val newBatch = batchedMessages :+ tx
      if (timeDeltaNanos(now) > minInterval.toNanos) {
        out ! TxBatch(newBatch)
        context.become(receiveSlow)
      } else {
        context.become(receiveFast(newBatch))
      }
      lastReceive = now
  }

  def receiveSlow: Receive = { case tx: TxUpdate =>
    val now = clock.instant()
    out ! tx
    if (timeDeltaNanos(now) <= minInterval.toNanos) {
      context.become(receiveFast(Vector()))
    }
    lastReceive = now
  }

  private def timeDeltaNanos(t: Instant): Long =
    Math.abs(ChronoUnit.NANOS.between(t, lastReceive))
}
