package actors

import actors.TxFilterAuthActor.Die
import akka.actor.{ActorRef, PoisonPill, Props}
import services.MemPoolWatcherService

object TxFilterNoAuthActor {
  case class Die()
  def props(out: ActorRef, filter: TxUpdate => Boolean, memPoolWatcher: MemPoolWatcherService): Props =
    Props(new TxFilterNoAuthActor(out, filter, memPoolWatcher))
}

class TxFilterNoAuthActor(val out: ActorRef, val filter: TxUpdate => Boolean,
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
