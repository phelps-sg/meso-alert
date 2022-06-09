package actors

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.pattern.pipe
import com.google.inject.Inject
import dao.{DuplicateWebhookException, Webhook, WebhookDao}
import org.apache.commons.logging.LogFactory
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json.{JsObject, Json, Writes}
import services.MemPoolWatcherService
import slick.DatabaseExecutionContext

import java.net.{URI, URLEncoder}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object WebhooksManagerActor {


  case class CreateActors(uri: URI, hook: Webhook)

  case class WebhookNotRegisteredException(uri: URI) extends Exception(s"No webhook registered for $uri")
  case class WebhookNotStartedException(uri: URI) extends Exception(s"No webhook started for $uri")
  case class WebhookAlreadyRegisteredException(uri: URI) extends Exception(s"Webhook already registered for $uri")
  case class WebhookAlreadyStartedException(uri: URI) extends Exception(s"Webhook already started for $uri")

  def props(memPoolWatcher: MemPoolWatcherService, backendSelection: HttpBackendSelection,
            messagingActorFactory: TxWebhookMessagingActor.Factory,
            filteringActorFactory: TxFilterNoAuthActor.Factory,
            webhookDao: WebhookDao,
            databaseExecutionContext: DatabaseExecutionContext): Props =
    Props(new WebhooksManagerActor(memPoolWatcher, backendSelection, messagingActorFactory, filteringActorFactory,
      webhookDao, databaseExecutionContext))

  implicit val startWrites: Writes[Started[Webhook]] = new Writes[Started[Webhook]]() {
    def writes(started: Started[Webhook]): JsObject = Json.obj(fields =
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

  var actors: Map[URI, Array[ActorRef]] = Map()

  import WebhooksManagerActor._

  def encodeUrl(url: String): String = URLEncoder.encode(url, "UTF-8")

  implicit class HookURI(uri: URI) {
    def withHook[R](fn: Webhook => R): Unit = {
      logger.debug(s"Querying hook for uri ${uri.toString}")
      webhookDao.findWebHookFor(uri) map {
        case Some(hook) => Success(fn(hook))
        case None => Failure(WebhookNotRegisteredException(uri))
      } pipeTo sender
    }
  }

  def fail(ex: Exception): Unit = {
    sender ! Failure(ex)
  }

  def provided(condition: => Boolean, block: => Unit, ex: => Exception): Unit = {
    if (condition) block else fail(ex)
  }

  override def receive: Receive = {

    case Register(hook: Webhook) =>
      webhookDao.insert(hook) map {
        _ => Success(Registered(hook))
      } recover {
        case DuplicateWebhookException(_) => Failure(WebhookAlreadyRegisteredException(hook.uri))
      } pipeTo sender

    case Start(uri: URI) =>
      logger.debug(s"Received start request for $uri")
      provided(!(actors contains uri), uri withHook (hook => {
        self ! CreateActors(uri, hook)
        Started(hook)
      }), WebhookAlreadyStartedException(uri))

    case Stop(uri: URI) =>
      provided (actors contains uri, {
        actors(uri).foreach(_ ! PoisonPill)
        actors -= uri
        uri withHook (hook => Stopped(hook))
      }, WebhookNotStartedException(uri))

    case CreateActors(uri, hook) =>
      val actorId = encodeUrl(uri.toURL.toString)
      val webhookMessagingActor =
        injectedChild(messagingActorFactory(uri), name = s"webhook-messenger-$actorId")
      val filteringActor =
        injectedChild(filteringActorFactory(webhookMessagingActor, _.value >= hook.threshold),
          name = s"webhook-filter-$actorId")
      actors += uri -> Array(webhookMessagingActor, filteringActor)

  }

}
