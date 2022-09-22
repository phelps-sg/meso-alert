package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import akka.actor.{Actor, Props}
import com.google.inject.Inject
import org.bitcoinj.core.StoredBlock
import play.api.Logging
import services.BlockChainProvider

object BlockChainWatcherActor {
  final case class NewBlock(block: StoredBlock)

  def props(blockChainProvider: BlockChainProvider): Props = Props(
    new BlockChainWatcherActor(blockChainProvider)
  )
}

class BlockChainWatcherActor @Inject() (
    val blockChainProvider: BlockChainProvider
) extends Actor
    with Logging
    with UnrecognizedMessageHandlerFatal {

  import actors.BlockChainWatcherActor._

  blockChainProvider.get.addNewBestBlockListener((block: StoredBlock) =>
    self ! NewBlock(block)
  )

  override def receive: Receive = {

    case NewBlock(block) =>
      logger.info(
        s"New best block ${block.getHeader.getHash} with height ${block.getHeight}"
      )

    case other =>
      unrecognizedMessage(other)

  }

}
