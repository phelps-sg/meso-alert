package services

import dao._
import actors._
import controllers.InitialisingController
import scala.concurrent.{ExecutionContext, Future}
import akka.actor.{ ActorSystem}
import com.google.inject.ImplementedBy
import play.api.Logging

import javax.inject.{Inject, Singleton}



@ImplementedBy(classOf[SlickTxManager])
trait SlickTxManagerService {
  def init(): Future[Unit]
}

@Singleton
class SlickTxManager @Inject()(val transactionUpdateDao: TransactionUpdateDao, val memPoolWatcher: MemPoolWatcherService)
                              (implicit system: ActorSystem, implicit val ec: ExecutionContext)

  extends SlickTxManagerService with Logging with InitialisingController {

  override def init(): Future[Unit] = {
    Future {
      logger.info("Starting slick tx manager... ")
      system.actorOf(TxPersistenceActor.props(transactionUpdateDao, memPoolWatcher))
    }
  }


}
