package actors

import akka.actor.Props
import com.google.inject.Inject
import dao.{Hook, SlackChatHookEncrypted, Webhook, WebhookDao}
import play.api.libs.json.{JsObject, Json, Writes}
import slick.DatabaseExecutionContext

import java.net.{URI, URLEncoder}
import java.time.Clock

object HooksManagerActorWeb {

  def props(
      messagingActorFactory: TxMessagingActorWeb.Factory,
      filteringActorFactory: TxFilterActor.Factory,
      batchingActorFactory: RateLimitingBatchingActor.Factory,
      webhookDao: WebhookDao,
      databaseExecutionContext: DatabaseExecutionContext,
      clock: Clock
  ): Props =
    Props(
      new HooksManagerActorWeb(
        messagingActorFactory,
        filteringActorFactory,
        batchingActorFactory,
        webhookDao,
        databaseExecutionContext,
        clock
      )
    )

  implicit val startWrites: Writes[Started[Hook[_]]] =
    new Writes[Started[Hook[_]]]() {
      def writes(started: Started[Hook[_]]): JsObject = {
        started match {

          case Started(hook: Webhook) =>
            Json.obj(
              fields = "uri" -> hook.uri.toString,
              "threshold" -> hook.threshold.value
            )

          case Started(hook: SlackChatHookEncrypted) =>
            Json.obj(
              fields = "channel" -> hook.channel.toString,
              "threshold" -> hook.threshold.value
            )

          case Started(x) =>
            throw new IllegalArgumentException(x.toString)

        }
      }
    }
}

class HooksManagerActorWeb @Inject() (
    val messagingActorFactory: TxMessagingActorWeb.Factory,
    val filteringActorFactory: TxFilterActor.Factory,
    val batchingActorFactory: RateLimitingBatchingActor.Factory,
    val dao: WebhookDao,
    val databaseExecutionContext: DatabaseExecutionContext,
    val clock: Clock
) extends HooksManagerActor[URI, Webhook] {

  override val hookTypePrefix: String = "webhook"
  override def encodeKey(uri: URI): String =
    URLEncoder.encode(uri.toString, "UTF-8")
}
