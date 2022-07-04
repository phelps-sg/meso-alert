package actors

import actors.AuthenticationActor.Die
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import com.google.inject.Inject
import services.MemPoolWatcherService
import dao._

object SlickManagerActor {

  trait Factory {
    def apply(out: ActorRef): Actor
  }
    case class Die()

    def props(slickTransactionUpdateDao: SlickTransactionUpdateDao, memPoolWatcher: MemPoolWatcherService ): Props =
        Props(new SlickManagerActor(slickTransactionUpdateDao, memPoolWatcher))


}

class SlickManagerActor @Inject()(val slickTransactionUpdateDao: SlickTransactionUpdateDao,
                                  val memPoolWatcher: MemPoolWatcherService)
  extends Actor with TxUpdateActor {


  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
    slickTransactionUpdateDao.init()
  }

  override def receive: Receive = {
    case tx: TxUpdate => slickTransactionUpdateDao.record(tx)
    case Die => self ! PoisonPill
  }

}