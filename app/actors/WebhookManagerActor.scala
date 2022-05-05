package actors

import actors.TxFilterAuthActor.Die
import akka.actor.{Actor, ActorRef, Props}
import com.google.inject.Inject
import org.apache.commons.logging.LogFactory
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json.{JsObject, Json, Writes}
import services.MemPoolWatcherService

import java.io.UnsupportedEncodingException
import java.net.{URI, URLEncoder}

object WebhookManagerActor {

  case class Webhook(uri: URI, threshold: Long)
  case class Register(hook: Webhook)
  case class Unregister(hook: Webhook)
  case class Started(hook: Webhook)
  case class Stopped(hook: Webhook)
  case class Registered(hook: Webhook)
  case class Start(uri: URI)
  case class Stop(uri: URI)
  case class List()

  case class WebhookNotRegisteredException(uri: URI) extends Exception(s"No webhook registered for $uri")

  def props(memPoolWatcher: MemPoolWatcherService, backendSelection: HttpBackendSelection,
            messagingActorFactory: TxWebhookMessagingActor.Factory,
            filteringActorFactory: TxFilterNoAuthActor.Factory): Props =
    Props(new WebhookManagerActor(memPoolWatcher, backendSelection, messagingActorFactory, filteringActorFactory))

  implicit val startWrites: Writes[Started] = new Writes[Started]() {
    def writes(started: Started): JsObject = Json.obj(fields =
        "uri" -> started.hook.uri,
        "threshold" -> started.hook.threshold
    )
  }
}

class WebhookManagerActor @Inject()(val memPoolWatcher: MemPoolWatcherService,
                                    val backendSelection: HttpBackendSelection,
                                    val messagingActorFactory: TxWebhookMessagingActor.Factory,
                                    val filteringActorFactory: TxFilterNoAuthActor.Factory)
  extends Actor with InjectedActorSupport {

  private val logger = LogFactory.getLog(classOf[WebhookManagerActor])

  import WebhookManagerActor._

  override def receive: Receive = updated(Map[URI, Webhook](), Map[URI, ActorRef]())

  def encodeUrl(url: String): String = URLEncoder.encode(url, "UTF-8")

  def updated(webhooks: Map[URI, Webhook], actors: Map[URI, ActorRef]): Receive = {
    case Register(hook) =>
      context.become(updated(webhooks = webhooks + (hook.uri -> hook), actors))
      sender ! Registered(hook)
    case Unregister(hook) =>
      context.become(updated(webhooks = webhooks.filterNot(_._2 == hook), actors))
    case Start(uri) =>
      logger.debug(s"Received start request for $uri")
      if (webhooks contains uri) {
        val hook = webhooks(uri)
        logger.debug(s"hook = " + hook)
        val actorId = encodeUrl(uri.toURL.toString)
        val webhookMessagingActor =
          injectedChild(messagingActorFactory(uri), name = s"webhook-messenger-$actorId")
        val filteringActor =
          injectedChild(filteringActorFactory(webhookMessagingActor, _.value >= hook.threshold),
                         name = s"webhook-filter-$actorId")
        context.become(updated(webhooks, actors = actors + (uri -> filteringActor)))
        sender ! Started(hook)
      } else {
        sender ! WebhookNotRegisteredException(uri)
      }
    case Stop(uri) =>
      actors(uri) ! Die
      sender ! Stopped(webhooks(uri))
    case List =>
      sender ! webhooks
  }

}
