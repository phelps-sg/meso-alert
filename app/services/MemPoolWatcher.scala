package services

import actors.Started
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.ImplementedBy
import com.google.inject.name.Named
import org.bitcoinj.core._
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.utils.BriefLogFormatter
import org.slf4j.{Logger, LoggerFactory}

import javax.inject.{Inject, Provider, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

@ImplementedBy(classOf[MemPoolWatcher])
trait MemPoolWatcherService {
  def addListener(listener: ActorRef): Unit
  def init(): Future[Try[Started[PeerGroup]]]
}

@ImplementedBy(classOf[MainNetPeerGroup])
trait PeerGroupSelection extends Provider[PeerGroup] {
  val params: NetworkParameters
  val get: PeerGroup
}

@Singleton
class MainNetPeerGroup extends PeerGroupSelection {
  val params: NetworkParameters = MainNetParams.get
  lazy val get = new PeerGroup(params)
}

@Singleton
class MemPoolWatcher @Inject()(@Named("mem-pool-actor") val actor: ActorRef)
                              (implicit system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends MemPoolWatcherService with ActorBackend {

  private val logger: Logger = LoggerFactory.getLogger(classOf[MemPoolWatcher])
  private val STATISTICS_FREQUENCY_MS: Long = 1000 * 60

  import actors.MemPoolWatcherActor._

  def startPeerGroup(): Future[Try[Started[PeerGroup]]] = {
   sendAndReceive(StartPeerGroup)
  }

  def run(): Future[Unit] = {
    Future {
      while (true) {
        Thread.sleep(STATISTICS_FREQUENCY_MS)
        printCounters()
      }
    }
  }

  def init(): Future[Try[Started[PeerGroup]]] = {
    logger.info("Starting peer group... ")
    BriefLogFormatter.initVerbose()
    val statistics = Future { run() }
    for {
      started <- startPeerGroup()
      _ <- statistics
    } yield started
  }

  def addListener(listener: ActorRef): Unit = {
    actor ! RegisterWatcher(listener)
  }

  private def printCounters(): Unit = {
    actor ! LogCounters
  }
}
