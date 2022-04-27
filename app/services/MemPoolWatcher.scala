package services

import actors.TxWatchActor
import akka.actor.ActorRef
import com.github.nscala_time.time.Imports.DateTime
import com.google.inject.ImplementedBy
import org.bitcoinj.core.{NetworkParameters, Peer, PeerGroup, Transaction}
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.ScriptException
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.wallet.{DefaultRiskAnalysis, RiskAnalysis}
import org.slf4j.{Logger, LoggerFactory}

import java.util
import java.util.Collections
import javax.inject.{Inject, Singleton}
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future
import scala.jdk.CollectionConverters.CollectionHasAsScala

@ImplementedBy(classOf[MemPoolWatcher])
trait MemPoolWatcherService {
  def addListener(listener: ActorRef): Unit
  def startDaemon(): Future[Unit]
}

@ImplementedBy(classOf[MainNetPeerGroup])
trait PeerGroupSelection {
  val peerGroup: PeerGroup
}

@Singleton
class MainNetPeerGroup extends PeerGroupSelection {
  val peerGroup = new PeerGroup(MainNetParams.get)
}

@Singleton
class MemPoolWatcher @Inject() (peerGroupSelection: PeerGroupSelection) extends MemPoolWatcherService {
  private val log: Logger = LoggerFactory.getLogger("mem-pool-watcher")
  private val PARAMS: NetworkParameters = MainNetParams.get
  private val NO_DEPS: util.List[Transaction] = Collections.emptyList
  private val counters: mutable.Map[String, Integer] = mutable.Map[String, Integer]().withDefaultValue(0)
  private val TOTAL_KEY: String = "TOTAL"
  private val START_MS: Long = System.currentTimeMillis
  private val STATISTICS_FREQUENCY_MS: Long = 1000 * 60

  BriefLogFormatter.initVerbose()
  val peerGroup: PeerGroup = peerGroupSelection.peerGroup

  def run(): Unit = {
    peerGroup.setMaxConnections(32)
    peerGroup.addPeerDiscovery(new DnsDiscovery(PARAMS))
    peerGroup.addOnTransactionBroadcastListener((_: Peer, tx: Transaction) => {
      val result: RiskAnalysis.Result = DefaultRiskAnalysis.FACTORY.create(null, tx, NO_DEPS).analyze
      incrementCounter(TOTAL_KEY)
      log.debug("tx {} result {}", tx.getTxId, result)
      incrementCounter(result.name)
      if (result eq RiskAnalysis.Result.NON_STANDARD) {
        incrementCounter(RiskAnalysis.Result.NON_STANDARD + "-" + DefaultRiskAnalysis.isStandard(tx))
      }
    })
    peerGroup.start()
    while (true) {
      Thread.sleep(STATISTICS_FREQUENCY_MS)
      printCounters()
    }
  }

  def startDaemon(): Future[Unit] = {
    Future {
      run()
    }
  }

  def addListener(listener: ActorRef): Unit = {
    peerGroup.addOnTransactionBroadcastListener((_: Peer, tx: Transaction) => {
      listener ! TxWatchActor.TxUpdate(
        hash = tx.getTxId.toString,
        value = tx.getOutputSum.value,
        time = DateTime.now(),
        isPending = tx.isPending,
        outputAddresses = tx.getOutputs.asScala.map( output =>
          try {
            Some(output.getScriptPubKey.getToAddress(PARAMS).toString)
          } catch {
            case _: ScriptException =>
              None
          }
        ).filterNot(_.isEmpty).map(_.get).toSeq)
    })
  }

  private def incrementCounter(name: String): Unit = {
    counters(name) += 1
  }

  //noinspection RedundantBlock
  private def printCounters(): Unit = {
    log.info(f"Runtime: ${(System.currentTimeMillis - START_MS) / 1000 / 60}%d minutes")
    for ((key, value) <- counters) {
      log.info(f"  $key%-40s${value}%6d  (${value.asInstanceOf[Int] * 100 / counters(TOTAL_KEY)}%d%% of total)")
    }
  }
}
