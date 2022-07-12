package services

import actors._
import akka.actor.ActorSystem
import com.google.inject.ImplementedBy
import controllers.InitialisingComponent
import dao._
import play.api.Logging

import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[SlickTxManager])
trait SlickTxManagerService {
  def init(): Future[Unit]
}

@Singleton
class SlickTxManager @Inject()(val transactionUpdateDao: TransactionUpdateDao, val memPoolWatcher: MemPoolWatcherService)
                              (implicit system: ActorSystem, implicit val ec: ExecutionContext)

  extends SlickTxManagerService with Logging with InitialisingComponent {

  override def init(): Future[Unit] = {
    Future {
      logger.info("Starting slick tx manager... ")
      system.actorOf(TxPersistenceActor.props(transactionUpdateDao, memPoolWatcher,ec))
    }
  }

}
