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

  val MAX_SECTIONS = 47
  val MAX_TXS_PER_SECTION = 20

  def txBatchToBlockMessage(messages: MessagesApi)(
      batch: TxBatch
  ): BlockMessage = {
    val toSections = txToSections(messages)(_)
    val sections =
      sectionsWithinLimits(
        messages(MESSAGE_TOO_MANY_TRANSACTIONS),
        batch.messages.flatMap(toSections).toVector
      )
    BlockMessage(sections)
  }

  def txToBlockMessage(messages: MessagesApi)(tx: TxUpdate): BlockMessage =
    BlockMessage(
      txToSections(messages)(tx)
    )

  def txToSections(messages: MessagesApi)(
      tx: TxUpdate
  ): Vector[Block] = {
    val headerAndTxHash = Vector(
      Header(
        s"${messages(MESSAGE_NEW_TRANSACTION)} ${formatSatoshi(tx.amount)} BTC"
      ),
      txHashSection(messages)(tx.hash, " " + messages(MESSAGE_TO_ADDRESSES))
    )

    sectionsWithinLimits(
      messages(MESSAGE_TOO_MANY_OUTPUTS),
      headerAndTxHash ++ txOutputsSections(tx.outputs)
    ) :+ Divider
  }

  def sectionsWithinLimits(
      message: String,
      allSections: Vector[Block]
  ): Vector[Block] =
    if (allSections.size > MAX_SECTIONS)
      allSections.take(MAX_SECTIONS) :+ Section(message)
    else
      allSections

  def txOutputsSections(outputs: Seq[TxInputOutput]): Vector[Section] = {
    val grouped = outputs.grouped(MAX_TXS_PER_SECTION) map { subOutputs =>
      Section(toAddresses(subOutputs).map(linkToAddress).mkString(", "))
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
