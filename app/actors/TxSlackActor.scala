package actors

import actors.TxFilterActor.TxInputOutput
import akka.actor.{Actor, Props}
import monix.eval.Task
import org.apache.commons.logging.LogFactory
import play.api.libs.json.Json
import sttp.client3._
import sttp.client3.asynchttpclient.monix._
import sttp.model.Uri

import java.net.URI
import scala.util.{Failure, Success}

object TxSlackActor {
  def props(hookUri: URI): Props = Props(new TxSlackActor(hookUri))
}

class TxSlackActor(val hookUri: URI) extends Actor {

  val blockChairBaseURL = "https://www.blockchair.com/bitcoin"
  private val logger = LogFactory.getLog(classOf[TxSlackActor])

  def linkToTxHash(hash: String) = s"<$blockChairBaseURL/transaction/$hash|$hash>"
  def linkToAddress(address: String) = s"<$blockChairBaseURL/address/$address|$address>"

  def formatSatoshi(value: Long): String = (value / 100000000L).toString

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

  override def receive: Receive = {
    case tx: TxUpdate =>

      val postTask = AsyncHttpClientMonixBackend().flatMap { backend =>
        val r = basicRequest
          .contentType("application/json")
          .body(Json.stringify(Json.obj("text" -> message(tx))))
          .post(Uri(javaUri = hookUri))

        r.send(backend)
          .flatMap { response => Task(logger.debug(s"""Got ${response.code} response, body:\n${response.body}""")) }
          .guarantee(backend.close())
      }

      import monix.execution.Scheduler.Implicits.global
      val f = postTask.runToFuture

      f onComplete {
        case Success(_) => logger.debug("Successfully posted message")
        case Failure(ex) => logger.warn(ex.getMessage)
      }
  }

}
