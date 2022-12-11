package dao

import actors.TxUpdate

trait Filter {
  def filter(tx: TxUpdate): Boolean
}

trait ThresholdFilter extends Filter {
  val threshold: Long
  def filter(tx: TxUpdate): Boolean = tx.value.value >= threshold
}
