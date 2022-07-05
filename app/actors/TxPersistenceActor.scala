package actors

import actors.AuthenticationActor.Die
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import com.google.inject.Inject
import services.MemPoolWatcherService
import dao._
import scala.util.{Failure, Success}
import scala.concurrent.Future



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
  override def process(tx: TxUpdate): Future[Int] = transactionUpdateDao.record(tx)

  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
    transactionUpdateDao.init()
  }
  

  override def receive: Receive = receiveDefault


}