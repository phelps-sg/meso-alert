package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import actors.RateLimitingBatchingActor.TxBatch
import actors.TxMessagingActorWeb.WebRequestFailedException
import akka.actor.Actor
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import dao.Webhook
import play.api.Logging
import play.api.libs.json.Json
import services.WebManagerService
import sttp.model.Uri
import util.BitcoinFormatting.toChatMessage

import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

object TxMessagingActorWeb {

  final case class WebRequestFailedException(statusCode: Int)
      extends Exception(s"HTTP request failed with status $statusCode")

  trait Factory extends TxMessagingActorFactory[Webhook] {
    def apply(@unused hook: Webhook): Actor
  }
}

class TxMessagingActorWeb @Inject() (
    webManager: WebManagerService,
    val random: Random,
    @Assisted hook: Webhook
)(implicit val ec: ExecutionContext)
    extends TxRetryOrDie[Unit, TxBatch]
    with UnrecognizedMessageHandlerFatal
    with Logging {

  override val maxRetryCount: Int = 3

  def success(): Unit = {
    logger.debug("Successfully posted message")
  }

  def process(tx: TxBatch): Future[Unit] = {
    val messageContent = tx.messages.map(toChatMessage).mkString("\n")
    val jsonMessage = Json.obj("text" -> messageContent)
    webManager.postJson(jsonMessage, Uri(hook.uri)) map { status =>
      if (!status.isSuccess) throw WebRequestFailedException(status.code)
    }
  }

  override def receive: Receive = {
    case tx: TxBatch => process(tx)
    case x           => unrecognizedMessage(x)
  }

}
