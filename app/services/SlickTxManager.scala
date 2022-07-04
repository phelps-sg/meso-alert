package services

import dao._
import actors._
import scala.concurrent.{ExecutionContext, Future}
import akka.actor.{ ActorSystem}
import com.google.inject.ImplementedBy
import org.bitcoinj.utils.BriefLogFormatter
import play.api.Logging

import javax.inject.{Inject, Singleton}



@ImplementedBy(classOf[SlickTxManager])
trait SlickTxManagerService {
  def init(): Future[Unit]
}

@Singleton
class SlickTxManager @Inject()(val slickTransactionUpdateDao: SlickTransactionUpdateDao, val memPoolWatcher: MemPoolWatcherService)
                              (implicit system: ActorSystem, implicit val executionContext: ExecutionContext)

  extends SlickTxManagerService with Logging {

  def init(): Future[Unit] = {
    Future {
      logger.info("Starting slick tx manager... ")
      BriefLogFormatter.initVerbose()
      system.actorOf(SlickManagerActor.props(slickTransactionUpdateDao, memPoolWatcher))
    }
  }


}
