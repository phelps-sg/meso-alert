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

  val blockChairBaseURL = "https://www.blockchair.com/bitcoin"
  def linkToTxHash(hash: String): String = s"<$blockChairBaseURL/transaction/$hash|$hash>"
  def linkToAddress(address: String): String = s"<$blockChairBaseURL/address/$address|$address>"

  def formatSatoshi(value: Long): String = {
    value match {
      case value if value >= 100000000 => (value / 100000000L).toString
      case _ => "%1.3f".format((value.toFloat / 100000000L))
    }
  }
  def formatOutputAddresses(outputs: Seq[TxInputOutput]): String =
    outputs.filterNot(_.address.isEmpty)
      .map(output => output.address.get)
      .distinct
      .map(output => linkToAddress(output))
      .mkString(", ")

  def message(tx: TxUpdate): String = {
    s"New transaction ${linkToTxHash(tx.hash)} with value ${formatSatoshi(tx.value)} BTC to " +
      s"addresses ${formatOutputAddresses(tx.outputs)}"
  }

}
