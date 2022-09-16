package util

import actors.{TxHash, TxInputOutput, TxUpdate}

object BitcoinFormatting {

  val blockChairBaseURL = "https://www.blockchair.com/bitcoin"

  def linkToTxHash(hash: TxHash): String =
    s"<$blockChairBaseURL/transaction/${hash.value}|${hash.value}>"

  def linkToAddress(address: String): String =
    s"<$blockChairBaseURL/address/$address|$address>"

  def formatSatoshi(value: Long): String = {
    value match {
      case value if value >= 100000000 => (value / 100000000L).toString
      case _                           => (value.toDouble / 100000000L).toString
    }
  }

  def formatOutputAddresses(outputs: Seq[TxInputOutput]): String =
    outputs
      .filterNot(_.address.isEmpty)
      .map(output => output.address.get)
      .distinct
      .map(output => linkToAddress(output))
      .mkString(", ")

  def message(tx: TxUpdate): String = {
    s"New transaction ${linkToTxHash(tx.hash)} with value ${formatSatoshi(tx.value)} BTC to " +
      s"addresses ${formatOutputAddresses(tx.outputs)}"
  }

}
