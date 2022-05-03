package actors

import actors.TxFilterAuthActor.Die
import akka.actor.{Actor, ActorRef, Props}
import org.apache.commons.logging.LogFactory
import play.api.libs.json.{JsObject, Json, Writes}
import services.MemPoolWatcherService

import java.net.URI

object WebhooksActor {

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

  def props(memPoolWatcher: MemPoolWatcherService): Props = Props(new WebhooksActor(memPoolWatcher))

  implicit val startWrites: Writes[Started] = new Writes[Started]() {
    def writes(started: Started): JsObject = Json.obj(fields =
        "uri" -> started.hook.uri,
        "threshold" -> started.hook.threshold
    )
  }
}

class WebhooksActor(val memPoolWatcher: MemPoolWatcherService) extends Actor {

  private val logger = LogFactory.getLog(classOf[WebhooksActor])

  import WebhooksActor._

  override def receive: Receive = updated(Map[URI, Webhook](), Map[URI, ActorRef]())

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
        val slackActor = context.actorOf(TxSlackActor.props(uri))
        val actor = context.actorOf(TxFilterNoAuthActor.props(slackActor, _.value >= hook.threshold, memPoolWatcher))
        context.become(updated(webhooks, actors = actors + (uri -> actor)))
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
