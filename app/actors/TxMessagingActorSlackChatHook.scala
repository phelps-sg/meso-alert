package actors

import akka.actor.Actor
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import com.slack.api.Slack
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import dao.SlackChannel
import org.slf4j.LoggerFactory
import play.api.Configuration
import slick.SlackChatExecutionContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

class TxMessagingActorSlackChatHook @Inject()(config : Configuration, sce: SlackChatExecutionContext,
                                              @Assisted channel: SlackChannel) extends Actor {

  implicit val executionContext: SlackChatExecutionContext = sce

  private val logger = LoggerFactory.getLogger(classOf[TxMessagingActorSlackChatHook])

  private val slack = Slack.getInstance()
  private val methods = slack.methods(config.get[String]("slack.bot_token"))

  def sendMessage(channelId: String, message: String): Future[ChatPostMessageResponse] = {

    val request = ChatPostMessageRequest.builder.channel(channelId).text(message).build

    val f = Future {
      methods.chatPostMessage(request)
    }

    f.onComplete {
      case Success(_) => logger.info(s"Successfully posted message $message to $channelId")
      case Failure(ex) =>
        logger.error(s"Could not post message $message to $channelId: ${ex.getMessage}")
        ex.printStackTrace()
    }

    f
  }

  override def receive: Receive = {
    case tx: TxUpdate =>
      logger.debug("Received $tx")
      sendMessage(channel.id, message(tx))
  }
}
