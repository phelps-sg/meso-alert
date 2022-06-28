package actors

import actors.AuthenticationActor.Die
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import services.MemPoolWatcherService

object TxFilterActor {

  trait Factory {
    def apply(out: ActorRef, filter: TxUpdate => Boolean): Actor
  }

  case class Die()

  def props(out: ActorRef, filter: TxUpdate => Boolean, memPoolWatcher: MemPoolWatcherService): Props =
    Props(new TxFilterActor(out, filter, memPoolWatcher))
}

class TxFilterActor @Inject()(@Assisted val out: ActorRef, @Assisted val filter: TxUpdate => Boolean,
                              val memPoolWatcher: MemPoolWatcherService)
  extends Actor with TxUpdateActor {

  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
  }

  override def receive: Receive = {
    case tx: TxUpdate => if (filter(tx)) out ! tx
    case Die => self ! PoisonPill
  }

}
