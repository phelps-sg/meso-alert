import actors.AuthenticationActor.TxInputOutput
import play.api.libs.json.{JsObject, Json, Writes}

import scala.collection.immutable.ArraySeq

//noinspection TypeAnnotation
package object actors {

  // scalafix:off
  implicit val txInputOutputWrites = new Writes[TxInputOutput] {
    def writes(inpOut: TxInputOutput): JsObject = {
      val addressField: Array[(String, Json.JsValueWrapper)] =
        Array(inpOut.address).filterNot(_.isEmpty).map("address" -> _.get)
      val valueField: Array[(String, Json.JsValueWrapper)] =
        Array(inpOut.value).filterNot(_.isEmpty).map("value" -> _.get)
      Json.obj(ArraySeq.unsafeWrapArray(addressField ++ valueField): _*)
    }
  }

  // scalafix:on
  val txsPerSection = 20
  val blockChairBaseURL = "https://www.blockchair.com/bitcoin"
  def linkToTxHash(hash: String): String =
    s"<$blockChairBaseURL/transaction/$hash|$hash>"
  def linkToAddress(address: String): String =
    s"<$blockChairBaseURL/address/$address|$address>"

  def formatSatoshi(value: Long): String = {
    value match {
      case value if value >= 100000000 => (value / 100000000L).toString
      case _                           => (value.toDouble / 100000000L).toString
    }
  }

  def message(tx: TxUpdate): String = {
    val outputs = tx.outputs
      .filterNot(_.address.isEmpty)
      .map(output => output.address.get)
      .distinct
    blockMessageBuilder(tx.hash, tx.value, outputs)
  }

  def buildOutputsSections(
      txOutputs: Seq[String],
      currentSectionOutputs: Int,
      totalSections: Int
  ): String = {
    if (totalSections > 47) {
      """"}}," + "{"type":"section","text":{"type":"mrkdwn",""" +
        """"text":"Transaction contains too many outputs to list here. Visit the Transaction URL to view all""" +
        """the outputs. "}},{"type":"divider"}]"""
    } else {
      if (currentSectionOutputs < txsPerSection && txOutputs.nonEmpty) {
        val newSectionString = s"${linkToAddress(txOutputs.head)}, "
        newSectionString + buildOutputsSections(
          txOutputs.tail,
          currentSectionOutputs + 1,
          totalSections
        )
      } else if (currentSectionOutputs >= txsPerSection && txOutputs.nonEmpty) {
        val newSectionString = """"}}, """ +
          """{"type":"section","text":{"type": "mrkdwn", "text": \""" +
          s"${linkToAddress(txOutputs.head)}, "
        newSectionString + buildOutputsSections(
          txOutputs.tail,
          1,
          totalSections + 1
        )
      } else {
        """"}}, {"type":"divider"}]"""
      }
    }
  }

  def blockMessageBuilder(
      txHash: String,
      txValue: Long,
      txOutputs: Seq[String]
  ): String =
    """[{"type":"header","text":{"type":"plain_text",""" +
      s""""text":"New Transaction With Value ${formatSatoshi(txValue)}""" +
      """ BTC","emoji":false}},{"type":"section","text":{"type":"mrkdwn",""" +
      s"""\"text\":\"Transaction Hash: ${linkToTxHash(
          txHash
        )} to addresses:\"}},""" +
      """{"type":"section","text":{"type": "mrkdwn", "text": """" +
      buildOutputsSections(txOutputs, 0, 3)
}
