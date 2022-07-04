package actors
import akka.actor._

trait TxRetryOrDie extends Actor {
  var maxRetryCount: Int

//  message types
  case class Retry(tx: TxUpdate, exception: Throwable)

  def retryOrDie(currentRetryCount: Int): Receive

}