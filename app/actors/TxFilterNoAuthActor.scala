package actors

import actors.TxFilterAuthActor.Die
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import org.slf4j.{Logger, LoggerFactory}
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
                                     val memPoolWatcher: MemPoolWatcherService)
  extends Actor with TxUpdateActor with TxForwardingActor {

  override val logger: Logger = LoggerFactory.getLogger(classOf[TxFilterNoAuthActor])

  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
  }

  override def receive: Receive = {
    case tx: TxUpdate => if (filter(tx)) forward(tx)
    case Die => self ! PoisonPill
  }

}
