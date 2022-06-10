package actors

import akka.actor.Props
import com.google.inject.Inject
import dao.{Webhook, WebhookDao}
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.json.{JsObject, Json, Writes}
import slick.DatabaseExecutionContext

import java.net.{URI, URLEncoder}

object WebhooksManagerActor {

  def props(messagingActorFactory: TxMessagingActorWeb.Factory,
            filteringActorFactory: TxFilterNoAuthActor.Factory,
            webhookDao: WebhookDao,
            databaseExecutionContext: DatabaseExecutionContext): Props =
    Props(new WebhooksManagerActor(messagingActorFactory, filteringActorFactory, webhookDao, databaseExecutionContext))

  implicit val startWrites: Writes[Started[Webhook]] = new Writes[Started[Webhook]]() {
    def writes(started: Started[Webhook]): JsObject = Json.obj(fields =
        "uri" -> started.hook.uri,
        "threshold" -> started.hook.threshold
    )
  }
}

class WebhooksManagerActor @Inject()(val messagingActorFactory: TxMessagingActorWeb.Factory,
                                     val filteringActorFactory: TxFilterNoAuthActor.Factory,
                                     val dao: WebhookDao,
                                     val databaseExecutionContext: DatabaseExecutionContext)
  extends HooksManagerActor[URI, Webhook] {

  override val logger: Logger = LoggerFactory.getLogger(classOf[WebhooksManagerActor])
  override val hookTypePrefix: String = "webhook"
  override def encodeKey(uri: URI): String = URLEncoder.encode(uri.toString, "UTF-8")
}
