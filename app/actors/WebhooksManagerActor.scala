package actors

import actors.WebhooksManagerActor.{CreateActors, HookAlreadyRegisteredException, HookAlreadyStartedException, HookNotRegisteredException, HookNotStartedException}
import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.pattern.pipe
import com.google.inject.Inject
import dao.{DuplicateWebhookException, HasThreshold, HookDao, Webhook, WebhookDao}
import org.apache.commons.logging.LogFactory
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json.{JsObject, Json, Writes}
import services.MemPoolWatcherService
import slick.DatabaseExecutionContext

import java.net.{URI, URLEncoder}
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

object WebhooksManagerActor {

  case class CreateActors[X, Y](uri: X, hook: Y)

  case class HookNotRegisteredException[X](uri: X) extends Exception(s"No webhook registered for $uri")
  case class HookNotStartedException[X](uri: X) extends Exception(s"No webhook started for $uri")
  case class HookAlreadyRegisteredException[Y](hook: Y) extends Exception(s"Webhook already registered with same key as $hook")
  case class HookAlreadyStartedException[X](uri: X) extends Exception(s"Webhook already started for $uri")

  def props(messagingActorFactory: TxWebhookMessagingActor.Factory,
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

trait HooksManagerActor[X, Y] extends Actor with InjectedActorSupport {

  private val logger = LogFactory.getLog(classOf[HooksManagerActor[X, Y]])

  val dao: HookDao[X, Y]
  val messagingActorFactory: HookActorFactory[X]
  val filteringActorFactory: TxFilterNoAuthActor.Factory
  val databaseExecutionContext: DatabaseExecutionContext

  var actors: Map[X, Array[ActorRef]] = Map()

  implicit val ec: ExecutionContext = databaseExecutionContext

  def hookTypePrefix: String
  def encodeKey(key: X): String

  implicit class HookFor(key: X) {
    def withHook[R](fn: Y => R): Unit = {
      dao.find(key) map {
        case Some(hook) => Success(fn(hook))
        case None => Failure(HookNotRegisteredException(key))
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

    case Register(hook: Y) =>
      dao.insert(hook) map {
        _ => Success(Registered(hook))
      } recover {
        case DuplicateWebhookException(_) => Failure(HookAlreadyRegisteredException(hook))
      } pipeTo sender

    case Start(uri: X) =>
      logger.debug(s"Received start request for $uri")
      provided(!(actors contains uri), uri withHook (hook => {
        self ! CreateActors(uri, hook)
        Started(hook)
      }), HookAlreadyStartedException(uri))

    case Stop(uri: X) =>
      provided (actors contains uri, {
        actors(uri).foreach(_ ! PoisonPill)
        actors -= uri
        uri withHook (hook => Stopped(hook))
      }, HookNotStartedException(uri))

    case CreateActors(uri: X, hook: HasThreshold) =>
      val actorId = encodeKey(uri)
      val webhookMessagingActor =
        injectedChild(messagingActorFactory(uri), name = s"$hookTypePrefix-messenger-$actorId")
      val filteringActor =
        injectedChild(filteringActorFactory(webhookMessagingActor, _.value >= hook.threshold),
          name = s"$hookTypePrefix-filter-$actorId")
      actors += uri -> Array(webhookMessagingActor, filteringActor)

  }

}

class WebhooksManagerActor @Inject()(val messagingActorFactory: TxWebhookMessagingActor.Factory,
                                     val filteringActorFactory: TxFilterNoAuthActor.Factory,
                                     val dao: WebhookDao,
                                     val databaseExecutionContext: DatabaseExecutionContext)
  extends HooksManagerActor[URI, Webhook] {

  override def encodeKey(uri: URI): String = URLEncoder.encode(uri.toString, "UTF-8")
  override def hookTypePrefix: String = "webhook"
}
