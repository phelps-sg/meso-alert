package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import akka.actor.{Actor, ActorRef, Props, Timers}
import com.google.inject.Inject
import dao._
import play.api.Logging
import services.MemPoolWatcherService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

object TxPersistenceActor {

  trait Factory {
    def apply(out: ActorRef): Actor
  }

  def props(
      transactionUpdateDao: TransactionUpdateDao,
      memPoolWatcher: MemPoolWatcherService,
      random: Random,
      ec: ExecutionContext
  ): Props =
    Props(
      new TxPersistenceActor(transactionUpdateDao, memPoolWatcher, random, ec)
    )

}

class TxPersistenceActor @Inject() (
    val transactionUpdateDao: TransactionUpdateDao,
    val memPoolWatcher: MemPoolWatcherService,
    val random: Random,
    implicit val ec: ExecutionContext
) extends Actor
    with TxUpdateActor
    with Logging
    with TxRetryOrDie[Int]
    with Timers
    with UnrecognizedMessageHandlerFatal {

  override val maxRetryCount = 3

  override def process(tx: TxUpdate): Future[Int] =
    transactionUpdateDao.record(tx)

  override def success(): Unit = logger.debug("Successfully added tx to db.")

  override def preStart(): Unit = {
    super.preStart()
    registerWithWatcher()
  }

}
