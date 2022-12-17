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
    val value: String
  }

  final case class Header(value: String) extends Block
  final case class Section(value: String) extends Block
  final case class Divider(value: String) extends Block

  object BlockMessage {
    def apply(components: Block*): BlockMessage =
      BlockMessage(s"[${components.map(_.value).mkString(",")}]")
  }
  final case class BlockMessage(value: String) extends AnyVal

  implicit val lang: Lang = Lang("en")

  val MESSAGE_NEW_TRANSACTION = "slackChat.newTransaction"
  val MESSAGE_NEW_TRANSACTIONS = "slackChat.newTransactions"
  val MESSAGE_TO_ADDRESSES = "slackChat.toAddresses"
  val MESSAGE_TRANSACTION_HASH = "slackChat.transactionHash"
  val MESSAGE_TOO_MANY_OUTPUTS = "slackChat.tooManyOutputs"

  val MAX_SECTIONS = 47
  val MAX_TXS_PER_SECTION = 20

  def txBatchToBlock(messages: MessagesApi)(
      batch: TxBatch
  ): BlockMessage = {
    val toBlock = txToBlock(messages)(_)
    val toSections = txToSections(messages)(_)
    if (batch.messages.size == 1) {
      toBlock(batch.messages.head)
    } else {
      val sections = sectionsWithinLimits(messages)(
        batch.messages.flatMap(toSections).toVector
      )
      BlockMessage(sections: _*)
    }
  }

  def txToBlock(messages: MessagesApi)(tx: TxUpdate): BlockMessage =
    BlockMessage(
      txToSections(messages)(tx): _*
    )

  def txToSections(messages: MessagesApi)(
      tx: TxUpdate
  ): Vector[Block] = {
    val headerAndTxHash = Vector(
      header(
        s"${messages(MESSAGE_NEW_TRANSACTION)} ${formatSatoshi(tx.amount)} BTC"
      ),
      txHashSection(messages)(tx.hash, " " + messages(MESSAGE_TO_ADDRESSES))
    )

    sectionsWithinLimits(messages)(
      headerAndTxHash ++ txOutputsSections(tx.outputs)
    ) :+ divider
  }

  def sectionsWithinLimits(
      messages: MessagesApi
  )(allSections: Vector[Block]): Vector[Block] =
    if (allSections.size > MAX_SECTIONS)
      allSections.take(MAX_SECTIONS) :+ tooManyOutputsSection(messages)
    else
      allSections

  val divider: Divider =
    Divider(
      """{"type":"divider"}"""
    )

  def tooManyOutputsSection(messages: MessagesApi): Section =
    markdown(
      messages(MESSAGE_TOO_MANY_OUTPUTS)
    )

  def header(text: String): Header =
    Header(
      s"""{"type":"header","text":{"type":"plain_text","text":"$text","emoji":false}}"""
    )

  def markdown(text: String): Section =
    Section(s"""{"type":"section","text":{"type":"mrkdwn","text":"$text"}}""")

  def txOutputsSections(outputs: Seq[TxInputOutput]): Vector[Section] = {
    val grouped = outputs.grouped(MAX_TXS_PER_SECTION) map { subOutputs =>
      markdown(toAddresses(subOutputs).map(linkToAddress).mkString(", "))
    }
    grouped.toVector
  }

  def txHashSection(
      messages: MessagesApi
  )(txHash: TxHash, postfix: String = ""): Section =
    markdown(s"${messages(MESSAGE_TRANSACTION_HASH)}: ${linkToTxHash(
        txHash
      )}$postfix")

}
