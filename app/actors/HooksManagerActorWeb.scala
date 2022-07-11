package actors

import akka.actor.Props
import com.google.inject.Inject
import dao.{Hook, SlackChatHookEncrypted, Webhook, WebhookDao}
import play.api.libs.json.{JsObject, Json, Writes}
import slick.DatabaseExecutionContext

import java.net.{URI, URLEncoder}

object HooksManagerActorWeb {

  def props(messagingActorFactory: TxMessagingActorWeb.Factory,
            filteringActorFactory: TxFilterActor.Factory,
            webhookDao: WebhookDao,
            databaseExecutionContext: DatabaseExecutionContext): Props =
    Props(new HooksManagerActorWeb(messagingActorFactory, filteringActorFactory, webhookDao, databaseExecutionContext))

  implicit val startWrites: Writes[Started[Hook[_]]] = new Writes[Started[Hook[_]]]() {
    def writes(started: Started[Hook[_]]): JsObject = {
      started match {
        case Started(hook: Webhook) =>
          Json.obj(fields =
            "uri" -> hook.uri.toString,
            "threshold" -> hook.threshold
          )
        case Started(hook: SlackChatHookEncrypted) =>
          Json.obj(fields =
            "channel" -> hook.channel.toString,
            "threshold" -> hook.threshold
          )
      }
    }
  }
}

class HooksManagerActorWeb @Inject()(val messagingActorFactory: TxMessagingActorWeb.Factory,
                                     val filteringActorFactory: TxFilterActor.Factory,
                                     val dao: WebhookDao,
                                     val databaseExecutionContext: DatabaseExecutionContext)
  extends HooksManagerActor[URI, Webhook] {

  override val hookTypePrefix: String = "webhook"
  override def encodeKey(uri: URI): String = URLEncoder.encode(uri.toString, "UTF-8")
}
