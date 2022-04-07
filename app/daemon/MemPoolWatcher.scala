package daemon

import org.bitcoinj.core.{NetworkParameters, Peer, PeerGroup, Transaction, TransactionBag}
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.utils.BriefLogFormatter
import org.bitcoinj.wallet.{DefaultRiskAnalysis, RiskAnalysis}
import org.slf4j.{Logger, LoggerFactory}

import java.util
import java.util.{Collections, HashMap, List, Map}
import scala.collection.mutable

object MemPoolWatcher {
  private val log: Logger = LoggerFactory.getLogger("mem-pool-watcher")
  private val PARAMS: NetworkParameters = MainNetParams.get
  private val NO_DEPS: util.List[Transaction] = Collections.emptyList
  private val counters: mutable.Map[String, Integer] = mutable.Map[String, Integer]().withDefaultValue(0)
  private val TOTAL_KEY: String = "TOTAL"
  private val START_MS: Long = System.currentTimeMillis
  private val STATISTICS_FREQUENCY_MS: Long = 1000 * 5

  @throws[InterruptedException]
  def main(args: Array[String]): Unit = {
    BriefLogFormatter.initVerbose()
    val peerGroup: PeerGroup = new PeerGroup(PARAMS)
    peerGroup.setMaxConnections(32)
    peerGroup.addPeerDiscovery(new DnsDiscovery(PARAMS))
    peerGroup.addOnTransactionBroadcastListener((peer: Peer, tx: Transaction) => {
        val result: RiskAnalysis.Result = DefaultRiskAnalysis.FACTORY.create(null, tx, NO_DEPS).analyze
        incrementCounter(TOTAL_KEY)
        log.info("tx {} result {}", tx.getTxId, result)
        incrementCounter(result.name)
        if (result eq RiskAnalysis.Result.NON_STANDARD)
          incrementCounter(RiskAnalysis.Result.NON_STANDARD + "-" + DefaultRiskAnalysis.isStandard(tx))
        log.info("value {}", tx.getOutputSum.value)
      }
    )
    peerGroup.start()
    while (true) {
      Thread.sleep(STATISTICS_FREQUENCY_MS)
      printCounters()
    }
  }

  private def incrementCounter(name: String): Unit = {
    var count: Integer = counters(name)
    counters(name) = count + 1
  }

  private def printCounters(): Unit = {
    System.out.printf("Runtime: %d minutes\n", (System.currentTimeMillis - START_MS) / 1000 / 60)
    val total: Integer = counters(TOTAL_KEY)
    if (total == null) return
    for ((key, value) <- counters) {
      System.out.printf("  %-40s%6d  (%d%% of total)\n", key, value, value.asInstanceOf[Int] * 100 / total)
    }
  }
}
