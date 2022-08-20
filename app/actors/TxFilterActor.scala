package actors

import actors.AuthenticationActor.Die
import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import play.api.Logging
import services.MemPoolWatcherService

import scala.annotation.unused

object TxFilterActor {

  trait Factory {
    def apply(@unused out: ActorRef, @unused filter: TxUpdate => Boolean): Actor
  }

  case class Die()

  def props(
      out: ActorRef,
      filter: TxUpdate => Boolean,
      memPoolWatcher: MemPoolWatcherService
  ): Props =
    Props(new TxFilterActor(out, filter, memPoolWatcher))
}

class TxFilterActor @Inject() (
    @Assisted val out: ActorRef,
    @Assisted val filter: TxUpdate => Boolean,
    val memPoolWatcher: MemPoolWatcherService
) extends Actor
    with TxUpdateActor
    with Logging
    with UnrecognizedMessageHandlerFatal {

  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
  }

  override def receive: Receive = {
    case tx: TxUpdate => if (filter(tx)) out ! tx
    case Die          => self ! PoisonPill
    case x            => unrecognizedMessage(x)
  }

}
