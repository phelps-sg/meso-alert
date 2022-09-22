package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import akka.actor.{Actor, ActorRef, Props}
import com.google.inject.Inject
import org.bitcoinj.core.StoredBlock
import org.bitcoinj.params.AbstractBitcoinNetParams
import play.api.Logging
import services.{BlockChainProvider, NetParamsProvider}

object BlockChainWatcherActor {

  final case class NewBlock(block: StoredBlock)
  final case class WatchTxConfidence(hash: TxHash, listener: ActorRef)

  def props(
      blockChainProvider: BlockChainProvider,
      netParamsProvider: NetParamsProvider
  ): Props = Props(
    new BlockChainWatcherActor(blockChainProvider, netParamsProvider)
  )
}

class BlockChainWatcherActor @Inject() (
    protected val blockChainProvider: BlockChainProvider,
    protected val netParamsProvider: NetParamsProvider
) extends Actor
    with Logging
    with UnrecognizedMessageHandlerFatal {

  import actors.BlockChainWatcherActor._

  implicit val params: AbstractBitcoinNetParams = netParamsProvider.get

  protected val blockChain = blockChainProvider.get

  protected var txWatchers: Map[TxHash, ActorRef] = Map()

  blockChain.addNewBestBlockListener((block: StoredBlock) =>
    self ! NewBlock(block)
  )

  override def receive: Receive = {

    case NewBlock(block) =>
      logger.info(
        s"New best block ${block.getHeader.getHash} with height ${block.getHeight}"
      )
      block.getHeader.getTransactions.forEach { tr =>
        txWatchers.get(TxHash(tr)) map { listener =>
          listener ! TxUpdate(tr)
        }
      }

    case WatchTxConfidence(hash: TxHash, listener) =>
      txWatchers += hash -> listener

    case other =>
      unrecognizedMessage(other)

  }

}
