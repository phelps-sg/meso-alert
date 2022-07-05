package actors

import actors.AuthenticationActor.Die
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import com.google.inject.Inject
import services.MemPoolWatcherService
import dao._
]import scala.util.{Failure, Success}



object TxPersistenceActor {

  trait Factory {
    def apply(out: ActorRef): Actor
  }

  def props(transactionUpdateDao: TransactionUpdateDao, memPoolWatcher: MemPoolWatcherService ): Props =
        Props(new TxPersistenceActor(transactionUpdateDao, memPoolWatcher))


}

class TxPersistenceActor @Inject()(val transactionUpdateDao: TransactionUpdateDao,
                                  val memPoolWatcher: MemPoolWatcherService)
  extends Actor with TxUpdateActor with TxRetryOrDie[Int] {

  override val maxRetryCount = 3
  override def process(tx: TxUpdate) = transactionUpdateDao.record(tx)

  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
    transactionUpdateDao.init()
  }
  

  override def receive: Receive = {
    case tx: TxUpdate => process(tx) onComplete {
      case Success(_) => logger.debug(s"Succesfuly added tx ${tx.hash} to db.")
      case Failure(ex) =>
        context.become(retryOrDie(0))
        self ! Retry(tx, ex)
    }
    case Die(reason)  =>
      logger.error(s"TxPersistenceActor terminating because $reason")
      self ! PoisonPill
  }


}