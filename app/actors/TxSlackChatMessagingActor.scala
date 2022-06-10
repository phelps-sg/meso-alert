package actors

import com.slack.api.Slack
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.SlackApiException
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import akka.actor.Actor
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import dao.SlackChannel
import org.slf4j.LoggerFactory
import play.api.Configuration
import slick.SlackChatExecutionContext

import scala.concurrent.Future

class TxSlackChatMessagingActor @Inject() (config : Configuration, sce: SlackChatExecutionContext,
                                           @Assisted channel: SlackChannel) extends Actor {

  implicit val executionContext: SlackChatExecutionContext = sce

  private val logger = LoggerFactory.getLogger(classOf[TxSlackChatMessagingActor])

  private val slack = Slack.getInstance()
  private val methods = slack.methods(config.get[String]("slackToken"))

  def sendMessage(channelId: String, message: String): Future[ChatPostMessageResponse] = {
    val request = ChatPostMessageRequest.builder.channel(channelId).text(message).build
    Future {
      methods.chatPostMessage(request)
    }
  }

  override def receive: Receive = {
    case tx: TxUpdate =>
      logger.debug("Received $tx")
      sendMessage(channel.id, message(tx))
  }
}
