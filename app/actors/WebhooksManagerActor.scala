package actors

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.pattern.pipe
import com.google.inject.Inject
import dao.{Webhook, WebhookDao}
import org.apache.commons.logging.LogFactory
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json.{JsObject, Json, Writes}
import services.MemPoolWatcherService
import slick.DatabaseExecutionContext

import java.net.{URI, URLEncoder}
import java.util.concurrent.ConcurrentHashMap
import scala.concurrent.ExecutionContext

object WebhooksManagerActor {

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
            filteringActorFactory: TxFilterNoAuthActor.Factory,
            webhookDao: WebhookDao,
            databaseExecutionContext: DatabaseExecutionContext): Props =
    Props(new WebhooksManagerActor(memPoolWatcher, backendSelection, messagingActorFactory, filteringActorFactory,
      webhookDao, databaseExecutionContext))

  implicit val startWrites: Writes[Started] = new Writes[Started]() {
    def writes(started: Started): JsObject = Json.obj(fields =
        "uri" -> started.hook.uri,
        "threshold" -> started.hook.threshold
    )
  }
}

class WebhooksManagerActor @Inject()(val memPoolWatcher: MemPoolWatcherService,
                                     val backendSelection: HttpBackendSelection,
                                     val messagingActorFactory: TxWebhookMessagingActor.Factory,
                                     val filteringActorFactory: TxFilterNoAuthActor.Factory,
                                     val webhookDao: WebhookDao,
                                     val databaseExecutionContext: DatabaseExecutionContext)
  extends Actor with InjectedActorSupport {

  private val logger = LogFactory.getLog(classOf[WebhooksManagerActor])

  implicit val ec: ExecutionContext = databaseExecutionContext

  val actors: java.util.concurrent.ConcurrentHashMap[URI, Array[ActorRef]] = new ConcurrentHashMap()

  import WebhooksManagerActor._

  def encodeUrl(url: String): String = URLEncoder.encode(url, "UTF-8")

  def withHookFor[R](uri: URI, fn: Webhook => R): Unit = {
    logger.debug(s"Querying hook for uri ${uri.toString}")
    webhookDao.forUri(uri).map({
      case Some(hook) => fn(hook)
      case None => WebhookNotRegisteredException(uri)
    }).pipeTo(sender)
  }

  override def receive: Receive = {

    case Register(hook) =>
      webhookDao.insert(hook).map(_ => Registered(hook)).pipeTo(sender)

    case Start(uri) =>
      logger.debug(s"Received start request for $uri")
      withHookFor(uri, {
        hook =>
            val actorId = encodeUrl(uri.toURL.toString)
            val webhookMessagingActor =
              injectedChild(messagingActorFactory(uri), name = s"webhook-messenger-$actorId")
            val filteringActor =
              injectedChild(filteringActorFactory(webhookMessagingActor, _.value >= hook.threshold),
                name = s"webhook-filter-$actorId")
            actors.put(uri, Array(webhookMessagingActor, filteringActor))
            Started(hook)
      })

    case Stop(uri) =>
      actors.get(uri).foreach(_ ! PoisonPill)
      withHookFor(uri, hook => Stopped(hook))

  }

}