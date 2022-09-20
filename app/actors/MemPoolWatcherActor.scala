package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import akka.actor.{Actor, ActorRef, Props}
import akka.pattern.pipe
import com.google.inject.Inject
import org.bitcoinj.core.{NetworkParameters, Peer, Transaction}
import org.bitcoinj.net.discovery.DnsDiscovery
import org.bitcoinj.wallet.{DefaultRiskAnalysis, RiskAnalysis}
import play.api.Logging
import services.PeerGroupSelection
import slick.DatabaseExecutionContext

import java.util
import java.util.Collections
import scala.concurrent.Future
import scala.util.{Failure, Success}

object MemPoolWatcherActor {

  trait MemPoolWatcherActorMessage
  final case class RegisterWatcher(listener: ActorRef)
      extends MemPoolWatcherActorMessage
  final case object StartPeerGroup extends MemPoolWatcherActorMessage
  final case class NewTransaction(tx: Transaction)
      extends MemPoolWatcherActorMessage
  final case class IncrementCounter(key: String)
      extends MemPoolWatcherActorMessage
  final case object LogCounters extends MemPoolWatcherActorMessage

  final case object PeerGroupAlreadyStartedException
      extends Exception("Peer group already started")

  def props(
      peerGroupSelection: PeerGroupSelection,
      databaseExecutionContext: DatabaseExecutionContext
  ): Props =
    Props(new MemPoolWatcherActor(peerGroupSelection, databaseExecutionContext))
}

class MemPoolWatcherActor @Inject() (
    val peerGroupSelection: PeerGroupSelection,
    val databaseExecutionContext: DatabaseExecutionContext
) extends Actor
    with Logging
    with UnrecognizedMessageHandlerFatal {

  private val TOTAL_KEY: String = "TOTAL"
  // noinspection ActorMutableStateInspection
  private val NO_DEPS: util.List[Transaction] = Collections.emptyList
  private val peerGroup = peerGroupSelection.get
  implicit val params: NetworkParameters = peerGroupSelection.params
  implicit val executionContext: DatabaseExecutionContext =
    databaseExecutionContext

  var counters: Map[String, Int] = Map().withDefaultValue(0)
  var startTime: Option[Long] = None

  import actors.MemPoolWatcherActor._

  override def receive: Receive = {

    case message: MemPoolWatcherActorMessage =>
      message match {

        case IncrementCounter(key: String) =>
          counters += (key -> (counters(key) + 1))

        case NewTransaction(tx: Transaction) =>
          val result: RiskAnalysis.Result =
            DefaultRiskAnalysis.FACTORY.create(null, tx, NO_DEPS).analyze
          self ! IncrementCounter(TOTAL_KEY)
          logger.debug(s"tx ${tx.getTxId} result $result")
          self ! IncrementCounter(result.name)
          if (result eq RiskAnalysis.Result.NON_STANDARD)
            self ! IncrementCounter(
              s"${RiskAnalysis.Result.NON_STANDARD} - ${DefaultRiskAnalysis.isStandard(tx)}"
            )

        case StartPeerGroup =>
          logger.debug("Received start peer group request.")

          startTime match {

            case None =>
              logger.debug("Starting peer group.")
              startTime = Some(System.currentTimeMillis())
              initialisePeerGroup()
              peerGroup.addOnTransactionBroadcastListener(
                (_: Peer, tx: Transaction) => self ! NewTransaction(tx)
              )

              Future {
                peerGroup.start()
              } map { _ =>
                Success(Started(peerGroup))
              } pipeTo sender()

            case Some(_) =>
              logger.debug("Peer group already started.")
              sender() ! Failure(PeerGroupAlreadyStartedException)
          }

        case LogCounters =>
          logger.debug("logging counters")
          logger.info(
            f"Runtime: ${(System.currentTimeMillis - startTime.get) / 1000 / 60}%d minutes"
          )
          for ((key, value) <- counters) {
            logger.info(
              f"  $key%-40s$value%6d  (${value * 100 / counters(TOTAL_KEY)}%d%% of total)"
            )
          }

        case RegisterWatcher(listener) =>
          logger.debug(s"Registering new listener $listener")
          peerGroup.addOnTransactionBroadcastListener(
            (_: Peer, tx: Transaction) => listener ! TxUpdate(tx)
          )
          logger.debug("registration completed")
      }

    case x =>
      unrecognizedMessage(x)

  }

  protected def initialisePeerGroup(): Unit = {
    peerGroup.setMaxConnections(32)
    peerGroup.addPeerDiscovery(new DnsDiscovery(params))
  }

}
