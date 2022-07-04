package actors

import actors.AuthenticationActor.Die
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import com.google.inject.Inject
import services.MemPoolWatcherService
import dao._
import scala.concurrent._
import scala.util.{Failure, Success}



object TxPersistenceActor {

  trait Factory {
    def apply(out: ActorRef): Actor
  }
    case class Die()

  implicit val ec: scala.concurrent.ExecutionContext = scala.concurrent.ExecutionContext.global
  def props(transactionUpdateDao: TransactionUpdateDao, memPoolWatcher: MemPoolWatcherService ): Props =
        Props(new TxPersistenceActor(transactionUpdateDao, memPoolWatcher))


}

class TxPersistenceActor @Inject()(val transactionUpdateDao: TransactionUpdateDao,
                                  val memPoolWatcher: MemPoolWatcherService)
                                  (implicit val ec: ExecutionContext)
  extends Actor with TxUpdateActor {


  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
    transactionUpdateDao.init()
  }

  override def receive: Receive = {
    case tx: TxUpdate => transactionUpdateDao.record(tx) onComplete {
      case Success(_) => logger.debug(s"Successfully added tx ${tx.hash} to database.")
      case Failure(ex) =>
        logger.error(s"Could not add tx ${tx.hash} to database: ${ex.getMessage}")
        self ! Die("TxPersistenceActor could not add tx to database.")
    }
    case Die => self ! PoisonPill
  }

}