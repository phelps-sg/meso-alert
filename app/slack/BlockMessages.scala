package slack

import actors.{TxHash, TxUpdate}
import play.api.i18n.{Lang, MessagesApi}
import util.BitcoinFormatting.{formatSatoshi, linkToAddress, linkToTxHash}

object BlockMessages {

  implicit val lang: Lang = Lang("en")

  val MESSAGE_NEW_TRANSACTION = "slackChat.newTransaction"
  val MESSAGE_TO_ADDRESSES = "slackChat.toAddresses"
  val MESSAGE_TRANSACTION_HASH = "slackChat.transactionHash"
  val MESSAGE_TOO_MANY_OUTPUTS = "slackChat.tooManyOutputs"

  val txsPerSection = 20

  def message(messages: MessagesApi)(tx: TxUpdate): String = {
    val outputs = tx.outputs
      .filterNot(_.address.isEmpty)
      .map(output => output.address.get)
      .distinct
    blockMessageBuilder(messages)(tx.hash, tx.value, outputs)
  }

  def buildOutputsSections(messages: MessagesApi)(
      txOutputs: Seq[String],
      currentSectionOutputs: Int,
      totalSections: Int
  ): String = {
    if (totalSections > 47) {
      """"}},{"type":"section","text":{"type":"mrkdwn",""" +
        s""""text":"${messages(
            MESSAGE_TOO_MANY_OUTPUTS
          )}"}},{"type":"divider"}]"""
    } else {
      if (currentSectionOutputs < txsPerSection && txOutputs.nonEmpty) {
        val newSectionString = s"${linkToAddress(txOutputs.head)}, "
        newSectionString + buildOutputsSections(messages)(
          txOutputs.tail,
          currentSectionOutputs + 1,
          totalSections
        )
      } else if (currentSectionOutputs >= txsPerSection && txOutputs.nonEmpty) {
        val newSectionString = """"}}, """ +
          """{"type":"section","text":{"type": "mrkdwn", "text": \""" +
          s"${linkToAddress(txOutputs.head)}, "
        newSectionString + buildOutputsSections(messages)(
          txOutputs.tail,
          1,
          totalSections + 1
        )
      } else {
        """"}}, {"type":"divider"}]"""
      }
    }
  }

  def blockMessageBuilder(messages: MessagesApi)(
      txHash: TxHash,
      txValue: Long,
      txOutputs: Seq[String]
  ): String =
    """[{"type":"header","text":{"type":"plain_text",""" +
      s""""text":"${messages(MESSAGE_NEW_TRANSACTION)} ${formatSatoshi(
          txValue
        )}""" +
      """ BTC","emoji":false}},{"type":"section","text":{"type":"mrkdwn",""" +
      s"""\"text\":\"${messages(MESSAGE_TRANSACTION_HASH)}: ${linkToTxHash(
          txHash
        )} ${messages(MESSAGE_TO_ADDRESSES)}:\"}},""" +
      """{"type":"section","text":{"type": "mrkdwn", "text": """" +
      buildOutputsSections(messages)(txOutputs, 0, 3)
}
