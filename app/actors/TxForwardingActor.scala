package actors

import akka.actor.ActorRef

trait TxForwardingActor {

  val out: ActorRef

  def forward(tx: TxUpdate): Unit = {
    out ! tx
  }
}
