package actors

import akka.actor.{Actor, ActorRef, Props}
import com.google.inject.Inject
import services.MemPoolWatcherService
import dao._
import scala.concurrent.{Future, ExecutionContext}

object TxPersistenceActor {

  trait Factory {
    def apply(out: ActorRef): Actor
  }

  def props(transactionUpdateDao: TransactionUpdateDao, memPoolWatcher: MemPoolWatcherService, ec: ExecutionContext ): Props =
        Props(new TxPersistenceActor(transactionUpdateDao, memPoolWatcher, ec))

}

class TxPersistenceActor @Inject()(val transactionUpdateDao: TransactionUpdateDao,
                                  val memPoolWatcher: MemPoolWatcherService, implicit val ec: ExecutionContext)
  extends Actor with TxUpdateActor with TxRetryOrDie[Int] {

  override val maxRetryCount = 3
  override def process(tx: TxUpdate): Future[Int] = transactionUpdateDao.record(tx)
  override def success(): Unit = logger.debug("Successfully added tx to db.")

  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
    transactionUpdateDao.init()
  }


}