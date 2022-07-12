package services

import actors._
import akka.actor.ActorSystem
import com.google.inject.ImplementedBy
import dao._
import play.api.Logging
import util.InitialisingComponent

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[SlickTxManager])
trait SlickTxManagerService {
  def initialiseFuture(): Future[Unit]
}

@Singleton
class SlickTxManager @Inject()(val transactionUpdateDao: TransactionUpdateDao, val memPoolWatcher: MemPoolWatcherService)
                              (implicit system: ActorSystem, implicit val ec: ExecutionContext)

  extends SlickTxManagerService with Logging with InitialisingComponent {

  initialise()

  override def initialiseFuture(): Future[Unit] = {
    Future {
      logger.info("Starting slick tx manager... ")
      system.actorOf(TxPersistenceActor.props(transactionUpdateDao, memPoolWatcher,ec))
    }
  }

}
