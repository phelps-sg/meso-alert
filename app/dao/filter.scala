package dao

import actors.TxUpdate

trait Filter {
  def filter(tx: TxUpdate): Boolean
}

trait ThresholdFilter extends Filter {
  val threshold: Satoshi
  def filter(tx: TxUpdate): Boolean = tx.amount.value >= threshold.value
}
