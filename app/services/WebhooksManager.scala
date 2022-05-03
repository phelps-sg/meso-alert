package services

import actors.WebhooksActor
import actors.WebhooksActor.{Register, Start, Started, Stop, Stopped, Webhook, WebhookNotRegisteredException}
import akka.actor.typed.receptionist.Receptionist.Registered
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.ImplementedBy

import java.net.URI
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[WebhooksManager])
trait SlackWebhooksManagerService {
  def start(uri: URI): Future[Started]
  def stop(uri: URI): Future[Stopped]
  def register(hook: Webhook): Future[Registered]
}

@Singleton
class WebhooksManager @Inject()(memPoolWatcher: MemPoolWatcher)
                               (implicit system: ActorSystem, executionContext: ExecutionContext)
  extends SlackWebhooksManagerService {

  val actor: ActorRef = system.actorOf(WebhooksActor.props(memPoolWatcher))

  implicit val timeout: Timeout = 1.minute

  val webHooks: Array[Webhook] = Array(
    Webhook(new URI("https://hooks.slack.com/services/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"),
      threshold = 200000000000L),
  )
  for (hook <- webHooks) {
    register(hook).map(_ => start(hook.uri))
  }

  def start(uri: URI): Future[Started] = {
    (actor ? Start(uri)).map {
      case x: Started => x
      case ex: WebhookNotRegisteredException => throw ex
    }
  }

  def stop(uri: URI): Future[Stopped] = {
    (actor ? Stop(uri)).map {
      case x: Stopped => x
    }
  }

  def register(hook: Webhook): Future[Registered] = {
    (actor ? Register(hook)).map {
      case x: Registered => x
    }
  }

}
