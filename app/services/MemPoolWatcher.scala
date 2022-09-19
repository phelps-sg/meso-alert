package services

import actors.Started
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.ImplementedBy
import com.google.inject.name.Named
import org.bitcoinj.core._
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.store.PostgresFullPrunedBlockStore
import org.bitcoinj.utils.BriefLogFormatter
import play.api.{Configuration, Logging}
import util.FutureInitialisingComponent

import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[MemPoolWatcher])
trait MemPoolWatcherService {
  def addListener(listener: ActorRef): Unit
//  def init(): Future[Started[PeerGroup]]
}

@ImplementedBy(classOf[MainNetPeerGroup])
trait PeerGroupSelection extends Provider[PeerGroup] {
  val params: NetworkParameters
  val get: PeerGroup
}

@Singleton
class MainNetPeerGroup @Inject() (
    protected val config: Configuration
) extends PeerGroupSelection {
  private val dbServerName =
    config.get[String]("meso-alert.db.properties.serverName")
  private val dbPortNumber =
    config.get[String]("meso-alert.db.properties.portNumber")
  private val dbUserName = config.get[String]("meso-alert.db.properties.user")
  private val dbPassword =
    config.get[String]("meso-alert.db.properties.password")
  private val dbDatabaseName =
    config.get[String]("meso-alert.db.properties.databaseName")
  val blockStore = new PostgresFullPrunedBlockStore(
    params,
    1000,
    dbServerName + ":" + dbPortNumber,
    dbDatabaseName,
    dbUserName,
    dbPassword
  )
  val params: NetworkParameters = MainNetParams.get
  val blockChain = new BlockChain(params, blockStore)
  lazy val get = new PeerGroup(params, blockChain)
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
    sendAndReceive(StartPeerGroup)
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
