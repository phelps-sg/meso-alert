package util

import actors.{TxHash, TxInputOutput, TxUpdate}
import dao.Satoshi

object BitcoinFormatting {

  val blockChairBaseURL = "https://www.blockchair.com/bitcoin"

  def linkToTxHash(hash: TxHash): String =
    s"<$blockChairBaseURL/transaction/${hash.value}|${hash.value}>"

  def linkToAddress(address: String): String =
    s"<$blockChairBaseURL/address/$address|$address>"

  def formatSatoshi(amount: Satoshi): String = {
    amount.value match {
      case value if value >= 100000000 =>
        (value / 100000000L).toString
      case value =>
        (value.toDouble / 100000000L).toString
    }
  }

  def toAddresses(inputOutputs: Seq[TxInputOutput]): Seq[String] =
    inputOutputs
      .filterNot(_.address.isEmpty)
      .map(_.address.get)
      .distinct

  def formatOutputAddresses(outputs: Seq[TxInputOutput]): String =
    toAddresses(outputs)
      .map(linkToAddress)
      .mkString(", ")

  def message(tx: TxUpdate): String = {
    s"New transaction ${linkToTxHash(tx.hash)} with value ${formatSatoshi(tx.amount)} BTC to " +
      s"addresses ${formatOutputAddresses(tx.outputs)}"
  }

}
