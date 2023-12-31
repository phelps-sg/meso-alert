package actors

import actors.RateLimitingBatchingActor.TxBatch
import akka.actor.Actor
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import dao.Webhook
import play.api.libs.json.Json
import services.WebManagerService
import sttp.model.{StatusCode, Uri}
import util.BitcoinFormatting.toChatMessage

import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Random

object TxMessagingActorWeb {

  trait Factory extends TxMessagingActorFactory[Webhook] {
    def apply(@unused hook: Webhook): Actor
  }
}

class TxMessagingActorWeb @Inject() (
    webManager: WebManagerService,
    val random: Random,
    @Assisted hook: Webhook
)(implicit val ec: ExecutionContext)
    extends RetryOrDieActor[StatusCode, TxBatch] {

  override val maxRetryCount: Int = 3

  override def process(tx: TxBatch): Future[StatusCode] = {
    val messageContent = tx.messages.map(toChatMessage).mkString("\n")
    val jsonMessage = Json.obj("text" -> messageContent)
    webManager.postJson(jsonMessage, Uri(hook.uri))
  }

}
