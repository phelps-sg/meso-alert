package slack

import actors.RateLimitingBatchingActor.TxBatch
import actors.{TxHash, TxInputOutput, TxUpdate}
import play.api.i18n.{Lang, MessagesApi}
import util.BitcoinFormatting.{
  formatSatoshi,
  linkToAddress,
  linkToTxHash,
  toAddresses
}

/** Functions for constructing
  * [[https://api.slack.com/messaging/composing/layouts rich text messages in Slack]].
  */
object BlockMessages {

  sealed trait Block {
    def render: String
  }

  final case class Header(text: String) extends Block {
    override def render: String =
      s"""{"type":"header","text":{"type":"plain_text","text":"$text","emoji":false}}"""
  }

  final case class Section(text: String) extends Block {
    override def render: String =
      s"""{"type":"section","text":{"type":"mrkdwn","text":"$text"}}"""
  }

  case object Divider extends Block {
    override def render: String = """{"type":"divider"}"""
  }

  case class BlockMessage(components: Seq[Block]) {
    def render: String = s"[${components.map(_.render).mkString(",")}]"
  }

  implicit val lang: Lang = Lang("en")

  val MESSAGE_NEW_TRANSACTION = "slackChat.newTransaction"
  val MESSAGE_NEW_TRANSACTIONS = "slackChat.newTransactions"
  val MESSAGE_TO_ADDRESSES = "slackChat.toAddresses"
  val MESSAGE_TRANSACTION_HASH = "slackChat.transactionHash"
  val MESSAGE_TOO_MANY_OUTPUTS = "slackChat.tooManyOutputs"
  val MESSAGE_TOO_MANY_TRANSACTIONS = "slackChat.tooManyTransactions"

  val MAX_BLOCKS = 47
  val MAX_TXS_PER_SECTION = 12

  def txBatchToBlockMessage(messages: MessagesApi)(
      batch: TxBatch
  ): BlockMessage = {
    val toSections = txToSections(messages)(_)
    val blocks =
      blocksWithinLimit(
        batch.messages.flatMap(toSections).toVector,
        messages(MESSAGE_TOO_MANY_TRANSACTIONS)
      )
    BlockMessage(blocks)
  }

  def txToBlockMessage(messages: MessagesApi)(tx: TxUpdate): BlockMessage =
    BlockMessage(
      txToSections(messages)(tx)
    )

  def txToSections(messages: MessagesApi)(
      tx: TxUpdate
  ): Seq[Block] = {
    val headerAndTxHash = Vector(
      Header(
        s"${messages(MESSAGE_NEW_TRANSACTION)} ${formatSatoshi(tx.amount)} BTC"
      ),
      txHashSection(messages)(tx.hash, " " + messages(MESSAGE_TO_ADDRESSES))
    )

    blocksWithinLimit(
      headerAndTxHash ++ txOutputsSections(tx.outputs),
      messages(MESSAGE_TOO_MANY_OUTPUTS)
    ) :+ Divider
  }

  def blocksWithinLimit(
      allBlocks: Seq[Block],
      limitExceededMessage: String
  ): Seq[Block] =
    if (allBlocks.size > MAX_BLOCKS)
      allBlocks.take(MAX_BLOCKS) :+ Section(limitExceededMessage)
    else
      allBlocks

  def txOutputsSections(outputs: Seq[TxInputOutput]): Seq[Section] = {
    val grouped = outputs.grouped(MAX_TXS_PER_SECTION) map { subOutputs =>
      val addresses = toAddresses(subOutputs).map(linkToAddress)
      Section {
        if (addresses.isEmpty)
          "<no address>"
        else
          addresses.mkString(", ")
      }
    }
    grouped.toVector
  }

  def txHashSection(
      messages: MessagesApi
  )(txHash: TxHash, postfix: String = ""): Section =
    Section(s"${messages(MESSAGE_TRANSACTION_HASH)}: ${linkToTxHash(
        txHash
      )}$postfix")

}
