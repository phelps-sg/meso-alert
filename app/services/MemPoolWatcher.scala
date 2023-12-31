package services

import actors.Started
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.ImplementedBy
import com.google.inject.name.Named
import org.bitcoinj.core._
import org.bitcoinj.utils.BriefLogFormatter
import play.api.Logging
import util.FutureInitialisingComponent

import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MemPoolWatcher])
trait MemPoolWatcherService {
  def addListener(listener: ActorRef): Unit
//  def init(): Future[Started[PeerGroup]]
}

@Singleton
class MemPoolWatcher @Inject() (@Named("mem-pool-actor") val actor: ActorRef)(
    implicit system: ActorSystem,
    implicit val executionContext: ExecutionContext
) extends MemPoolWatcherService
    with ActorBackend
    with Logging
    with FutureInitialisingComponent {

  initialise()

  private def statisticsInitialDelay: FiniteDuration = 1.minute
  private def statisticsInterval: FiniteDuration = 1.minute

  import actors.MemPoolWatcherActor._

  override def initialiseFuture(): Future[Unit] = {
    logger.info("Starting peer group... ")
    BriefLogFormatter.initVerbose()
    scheduleStatistics()
    startPeerGroup() map { _ => () }
  }

  def startPeerGroup(): Future[Started[PeerGroup]] = {
    sendAndReceive[MemPoolWatcherActorMessage, Started[PeerGroup]](
      StartPeerGroup
    )
  }

  def addListener(listener: ActorRef): Unit = {
    actor ! RegisterWatcher(listener)
  }

  private def scheduleStatistics() = {
    logger.debug(s"actor = $actor")
    system.scheduler.scheduleWithFixedDelay(
      statisticsInitialDelay,
      statisticsInterval,
      actor,
      LogCounters
    )
  }
}
