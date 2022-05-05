package actors

import actors.TxFilterAuthActor.Die
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import services.MemPoolWatcherService

object TxFilterNoAuthActor {

  trait Factory {
    def apply(out: ActorRef, filter: TxUpdate => Boolean): Actor
  }

  case class Die()

  def props(out: ActorRef, filter: TxUpdate => Boolean, memPoolWatcher: MemPoolWatcherService): Props =
    Props(new TxFilterNoAuthActor(out, filter, memPoolWatcher))
}

class TxFilterNoAuthActor @Inject() (@Assisted val out: ActorRef, @Assisted val filter: TxUpdate => Boolean,
                          memPoolWatcher: MemPoolWatcherService)
  extends AbstractTxUpdateActor(memPoolWatcher) with TxForwardingActor {

  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
  }

  override def receive: Receive = {
    case tx: TxUpdate => if (filter(tx)) forward(tx)
    case Die => self ! PoisonPill
  }

}
