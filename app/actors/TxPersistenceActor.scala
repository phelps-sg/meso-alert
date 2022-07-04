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
  extends Actor with TxUpdateActor with TxRetryOrDie {

  override var maxRetryCount = 3


  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
    transactionUpdateDao.init()
  }

  override def receive: Receive = {
    case tx: TxUpdate => transactionUpdateDao.record(tx) onComplete {
      case Success(_) => logger.debug(s"Successfully added tx ${tx.hash} to database.")
      case Failure(ex) =>
        context.become(retryOrDie(0))
        self ! Retry(tx, ex)
    }
    case Die(reason)  =>
      logger.error(s"TxPersistenceActor terminating because $reason")
      self ! PoisonPill
  }

  override def retryOrDie(currentRetryCount: Int): Receive = {
    case Retry(tx, _) if currentRetryCount < maxRetryCount =>
      logger.error(s"Could not add tx ${tx.hash} to database. Retrying ...")
      transactionUpdateDao.record(tx) onComplete {
        case Success(_) =>
          logger.debug(s"Successfully added tx ${tx.hash} to database.")
          context.become(receive)
        case Failure(ex) =>
          context.become(retryOrDie(currentRetryCount + 1))
          self ! Retry(tx, ex)
      }
    case Retry(tx, ex) if currentRetryCount >= maxRetryCount =>
      logger.error(s"Could not add tx ${tx.hash} to database: ${ex.getMessage}")
      context.become(receive)
      self ! Die("could not add tx to database.")
  }

}