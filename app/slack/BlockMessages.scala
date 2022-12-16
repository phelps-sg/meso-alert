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

object BlockMessages {

  sealed trait BlockComponent {
    val value: String
  }

  final case class Header(value: String) extends BlockComponent
  final case class Section(value: String) extends BlockComponent
  final case class Divider(value: String) extends BlockComponent

  object Block {
    def apply(components: BlockComponent*): Block =
      Block(s"[${components.map(_.value).mkString(",")}]")
  }
  final case class Block(value: String) extends AnyVal

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
  ): Block = {
    val toBlock = txToBlock(messages)(_)
    if (batch.messages.size == 1) {
      toBlock(batch.messages.head)
    } else {
      Block(
        """[{"type":"header","text":{"type":"plain_text",""" +
          s""""text":"${batch.messages.size} ${messages(
              MESSAGE_NEW_TRANSACTIONS
            )}"""
      )
    }
  }

  def txToBlock(messages: MessagesApi)(
      tx: TxUpdate
  ): Block = {

    val headerAndTxHash = Vector(
      header(
        s"${messages(MESSAGE_NEW_TRANSACTION)} ${formatSatoshi(tx.amount)} BTC"
      ),
      txHashSection(messages)(tx.hash, " " + messages(MESSAGE_TO_ADDRESSES))
    )

    val allSections =
      headerAndTxHash ++ txOutputsSections(tx.outputs) :+ divider

    val messageSections =
      if (allSections.size > MAX_SECTIONS)
        allSections.take(MAX_SECTIONS) :+ tooManyOutputsSection(messages)
      else
        allSections

    Block(messageSections: _*)
  }

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
