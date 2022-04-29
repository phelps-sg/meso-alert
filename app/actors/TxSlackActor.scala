package actors

import akka.actor.{Actor, Props}
import sttp.client3._
import sttp.client3.circe._
import sttp.client3.asynchttpclient.monix._
import io.circe.generic.auto._
import monix.eval.Task
import org.apache.commons.logging.LogFactory
import play.api.libs.json.Json
import sttp.model.Uri

import scala.util.{Failure, Success}

object TxSlackActor {

  def props(): Props = Props(new TxSlackActor())

}

class TxSlackActor extends Actor {

  private val logger = LogFactory.getLog(classOf[TxSlackActor])

//  val uri = Uri("https://hooks.slack.com/services/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")

  case class SlackMessage(text: TxUpdate)

  override def receive: Receive = {
    case tx: TxUpdate =>

      val postTask = AsyncHttpClientMonixBackend().flatMap { backend =>
        val r = basicRequest
          .contentType("application/json")
          .body(Json.stringify(Json.obj("text" -> Json.stringify(txUpdateWrites.writes(tx)))))
//          .body(Json.stringify(Json.obj("text" -> "hello")))
//          .body("{\"text\": \"hello\"}")
          .post(uri"https://hooks.slack.com/services/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")

        r.send(backend)
          .flatMap { response => Task(logger.info(s"""Got ${response.code} response, body:\n${response.body}""")) }
          .guarantee(backend.close())

      }

      import monix.execution.Scheduler.Implicits.global
      val f = postTask.runToFuture

      f onComplete {
        case Success(_) => logger.info("Successfully posted message")
        case Failure(_) => logger.error("Failed")
      }
//      postTask.runSyncUnsafe()
  }

}
