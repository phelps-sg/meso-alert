package daemon

import actors.TxWatchActor
import akka.actor.ActorRef
import com.github.nscala_time.time.Imports.DateTime
import org.bitcoinj.core.{NetworkParameters, Peer, PeerGroup, Transaction}
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.wallet.{DefaultRiskAnalysis, RiskAnalysis}
import org.slf4j.{Logger, LoggerFactory}

import java.util
import java.util.Collections
import scala.collection.mutable
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class MemPoolWatcher(listener: ActorRef) {
  private val log: Logger = LoggerFactory.getLogger("mem-pool-watcher")
  private val PARAMS: NetworkParameters = MainNetParams.get
  private val NO_DEPS: util.List[Transaction] = Collections.emptyList
  private val counters: mutable.Map[String, Integer] = mutable.Map[String, Integer]().withDefaultValue(0)
  private val TOTAL_KEY: String = "TOTAL"
  private val START_MS: Long = System.currentTimeMillis
  private val STATISTICS_FREQUENCY_MS: Long = 1000 * 5

  def run(): Unit = {
    BriefLogFormatter.initVerbose()
    val peerGroup: PeerGroup = new PeerGroup(PARAMS)
    peerGroup.setMaxConnections(32)
    peerGroup.addPeerDiscovery(new DnsDiscovery(PARAMS))
    peerGroup.addOnTransactionBroadcastListener((_: Peer, tx: Transaction) => {
      val result: RiskAnalysis.Result = DefaultRiskAnalysis.FACTORY.create(null, tx, NO_DEPS).analyze
      incrementCounter(TOTAL_KEY)
      log.info("tx {} result {}", tx.getTxId, result)
      incrementCounter(result.name)
      if (result eq RiskAnalysis.Result.NON_STANDARD) {
        incrementCounter(RiskAnalysis.Result.NON_STANDARD + "-" + DefaultRiskAnalysis.isStandard(tx))
      }
      listener ! TxWatchActor.TxUpdate(tx.getTxId.toString, tx.getOutputSum.value, DateTime.now())
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
