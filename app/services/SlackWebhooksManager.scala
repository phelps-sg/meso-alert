package services

import actors.{TxAuthActor, TxFilterNoAuthActor, TxSlackActor}
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.ImplementedBy

import java.net.URI
import javax.inject.{Inject, Singleton}

@ImplementedBy(classOf[SlackWebhooksManager])
trait SlackWebhooksManagerService {
  def start(): Unit
}

@Singleton
class SlackWebhooksManager @Inject() (memPoolWatcher: MemPoolWatcher, userManager: UserManager)
                                     (implicit system: ActorSystem) extends SlackWebhooksManagerService {

  case class Webhook(uri: URI, threshold: Long)

  val webHooks: Array[Webhook] = Array(
    Webhook(new URI("https://hooks.slack.com/services/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"),
      threshold = 200000000000L),
  )

  def startWebhook(webhook: Webhook): ActorRef = {
    val slackActor = system.actorOf(TxSlackActor.props(webhook.uri))
    val watchActor =
      system.actorOf(TxFilterNoAuthActor.props(slackActor, _.value >= webhook.threshold, memPoolWatcher))
    watchActor
  }

  def start(): Unit = {
    webHooks.foreach(startWebhook)
  }
}
