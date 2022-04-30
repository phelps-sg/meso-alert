package actors

import akka.actor.{ActorRef, Props}
import services.MemPoolWatcherService

object TxFilterNoAuthActor {
  def props(out: ActorRef, filter: TxUpdate => Boolean, memPoolWatcher: MemPoolWatcherService): Props =
    Props(new TxFilterNoAuthActor(out, filter, memPoolWatcher))
}

class TxFilterNoAuthActor(val out: ActorRef, val filter: TxUpdate => Boolean,
                          memPoolWatcher: MemPoolWatcherService)
  extends AbstractTxUpdateActor(memPoolWatcher) with TxForwardingActor {

  override def receive: Receive = {
    case tx: TxUpdate => if (filter(tx)) forward(tx)
  }

}
