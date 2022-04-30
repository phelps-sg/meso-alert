package services

import actors.{TxFilterActor, TxSlackActor}
import akka.actor.{ActorRef, ActorSystem}

import javax.inject.{Inject, Singleton}

@Singleton
class SlackWebhooksManager @Inject() (memPoolWatcher: MemPoolWatcher, userManager: UserManager)
                                     (implicit system: ActorSystem) {

  val webHooks: Array[String] = Array(
    "https://hooks.slack.com/services/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",
  )

  def startWebhook(uri: String): ActorRef = {
    val slackActor = system.actorOf(TxSlackActor.props(uri))
    val slackWatchActor = system.actorOf(TxFilterActor.props(slackActor, memPoolWatcher, userManager))
    slackWatchActor ! TxFilterActor.Auth("guest", "test")
    slackWatchActor
  }

  def start(): Unit = {
    webHooks.foreach(startWebhook)
  }
}
