package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import akka.actor.{Actor, ActorRef, Props}
import com.google.inject.Inject
import org.bitcoinj.core.{AbstractBlockChain, StoredBlock}
import org.bitcoinj.params.AbstractBitcoinNetParams
import play.api.Logging
import services.{BlockChainProvider, NetParamsProvider}

object BlockChainWatcherActor {

  sealed trait BlockChainWatcherMessage
  final case class NewBlock(block: StoredBlock) extends BlockChainWatcherMessage
  final case class WatchTxConfidence(hash: TxHash, listener: ActorRef)
      extends BlockChainWatcherMessage

  def props(
      blockChainProvider: BlockChainProvider,
      netParamsProvider: NetParamsProvider
  ): Props = Props(
    new BlockChainWatcherActor(blockChainProvider, netParamsProvider)
  )
}

/** This actor allows other actors to register to receive updates on a specified
  * transaction hash by sending a `WatchTxConfidence` message. When a new block
  * is broadcast, the receiving actor is informed of any transactions matching
  * the given hash through a `TxUpdate` event.
  */
class BlockChainWatcherActor @Inject() (
    protected val blockChainProvider: BlockChainProvider,
    protected val netParamsProvider: NetParamsProvider
) extends Actor
    with Logging
    with UnrecognizedMessageHandlerFatal {

  import actors.BlockChainWatcherActor._

  implicit val params: AbstractBitcoinNetParams = netParamsProvider.get

  protected val blockChain: AbstractBlockChain = blockChainProvider.get

  protected var txWatchers: Map[TxHash, ActorRef] = Map()

  logger.debug(s"Adding $self as new best block listener")
  blockChain.addNewBestBlockListener((block: StoredBlock) =>
    self ! NewBlock(block)
  )

  override def receive: Receive = {

    case message: BlockChainWatcherMessage =>
      message match {

        case NewBlock(block) =>
          logger.info(
            s"New best block ${block.getHeader.getHash} with height ${block.getHeight}"
          )
          Option(block.getHeader.getTransactions) map { transactions =>
            transactions.forEach { tr =>
              txWatchers.get(TxHash(tr)) map { listener =>
                listener ! TxUpdate(tr)
              }
            }
          }

        case WatchTxConfidence(hash: TxHash, listener) =>
          txWatchers += hash -> listener

      }

    case other =>
      unrecognizedMessage(other)

  }

}
