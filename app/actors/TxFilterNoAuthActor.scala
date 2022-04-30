package actors

import akka.actor.ActorRef
import services.MemPoolWatcherService

class TxFilterNoAuthActor(val out: ActorRef, val filter: TxUpdate => Boolean,
                          memPoolWatcher: MemPoolWatcherService)
  extends AbstractTxUpdateActor(memPoolWatcher) with TxForwardingActor {

  override def receive: Receive = {
    case tx: TxUpdate => if (filter(tx)) forward(tx)
  }

}
