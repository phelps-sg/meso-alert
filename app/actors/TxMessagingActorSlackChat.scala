package actors

import actors.TxMessagingActorSlackChat.BoltException
import akka.actor.Actor
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import com.slack.api.methods.AsyncMethodsClient
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import dao.SlackChatHook
import play.api.{Configuration, Logging}
import slack.SlackClient
import slick.SlackChatExecutionContext

import scala.concurrent.Future
import scala.jdk.FutureConverters._
import scala.util.Random

object TxMessagingActorSlackChat  {

  trait Factory extends TxMessagingActorFactory[SlackChatHook] {
    def apply(hook: SlackChatHook): Actor
  }

  case class BoltException(msg: String) extends Exception(msg)
}

class TxMessagingActorSlackChat @Inject()(protected val config : Configuration, sce: SlackChatExecutionContext,
                                          val random: Random,
                                          @Assisted hook: SlackChatHook)
  extends Actor with TxRetryOrDie[ChatPostMessageResponse] with SlackClient with Logging {

  protected val slackMethods: AsyncMethodsClient = slack.methodsAsync(hook.token)
  implicit val ec: SlackChatExecutionContext = sce
  override val maxRetryCount = 3

  override def success(): Unit = logger.info("Successfully posted message")

  override def process(tx: TxUpdate): Future[ChatPostMessageResponse] = {
    val msg = message(tx)
    val request = ChatPostMessageRequest.builder
      .token(hook.token)
      .username("meso-alert")
      .channel(hook.channel.id)
      .text(msg)
      .build
    logger.debug(s"Submitting request: $request")
    val chatPostMessageFuture = slackMethods.chatPostMessage(request).asScala
    chatPostMessageFuture map {
      case response if response.isOk => response
      case response if !response.isOk => throw BoltException(response.getError)
    }
  }
}
