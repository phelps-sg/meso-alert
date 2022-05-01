package actors

import actors.MemPoolWatcherActor.RegisterWatcher
import akka.actor.{Actor, ActorRef, Props}
import org.bitcoinj.core.{NetworkParameters, Peer, PeerGroup, Transaction}

object MemPoolWatcherActor {
  case class RegisterWatcher(listener: ActorRef)
  def props(peerGroup: PeerGroup)(implicit params: NetworkParameters): Props =
    Props(new MemPoolWatcherActor(peerGroup))
}

class MemPoolWatcherActor(val peerGroup: PeerGroup)(implicit val params: NetworkParameters) extends Actor {

  override def receive: Receive = {
    case register: RegisterWatcher =>
      peerGroup.addOnTransactionBroadcastListener((_: Peer, tx: Transaction) =>
        register.listener ! TxUpdate(tx))
  }

}
