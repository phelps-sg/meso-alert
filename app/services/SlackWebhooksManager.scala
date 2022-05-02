package services

import actors.WebhooksActor
import actors.WebhooksActor.{Register, Start, Started, Stop, Stopped, Webhook}
import akka.actor.typed.receptionist.Receptionist.Registered
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.{AskSupport, ask}
import akka.util.Timeout
import com.google.inject.ImplementedBy

import java.net.URI
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[SlackWebhooksManager])
trait SlackWebhooksManagerService {
  def start(uri: String): Future[Started]
  def stop(uri: String): Future[Stopped]
  def register(uri: String, threshold: Long): Future[Registered]
}

@Singleton
class SlackWebhooksManager @Inject() (memPoolWatcher: MemPoolWatcher, userManager: UserManager)
                                     (implicit system: ActorSystem, executionContext: ExecutionContext)
  extends SlackWebhooksManagerService {

  val actor: ActorRef = system.actorOf(WebhooksActor.props(memPoolWatcher))

  implicit val timeout: Timeout = 1.minute

  val webHooks: Array[Webhook] = Array(
    Webhook(new URI("https://hooks.slack.com/services/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"),
      threshold = 200000000000L),
  )
  for (hook <- webHooks) {
    register(hook.uri.toString, hook.threshold).map(_ => start(hook.uri.toString))
    start(hook.uri.toString)
  }

  def start(uri: String): Future[Started] = {
    (actor ? Start(new URI(uri))).map {
      case x: Started => x
    }
  }

  def stop(uri: String): Future[Stopped] = {
    (actor ? Stop(new URI(uri))).map {
      case x: Stopped => x
    }
  }

  def register(uri: String, threshold: Long): Future[Registered] = {
    (actor ? Register(Webhook(new URI(uri), threshold))).map {
      case x: Registered => x
    }
  }

}
