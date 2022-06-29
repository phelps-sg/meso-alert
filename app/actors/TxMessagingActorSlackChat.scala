package actors

import akka.actor.Actor
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import com.slack.api.Slack
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import dao.SlackChannel
import play.api.{Configuration, Logging}
import slick.SlackChatExecutionContext

import scala.concurrent.Future
import scala.util.{Failure, Success}

import scala.jdk.FutureConverters._

object TxMessagingActorSlackChat  {

  trait Factory extends TxMessagingActorFactory[SlackChannel] {
    def apply(channel: SlackChannel): Actor
  }
}

class TxMessagingActorSlackChat @Inject()(config : Configuration, sce: SlackChatExecutionContext,
                                          @Assisted channel: SlackChannel) extends Actor with Logging {

  implicit val executionContext: SlackChatExecutionContext = sce

  private val slack = Slack.getInstance()
  private val token = config.get[String]("slack.botToken")
  private val methods = slack.methodsAsync(token)

  def sendMessage(channelId: String, message: String): Future[ChatPostMessageResponse] = {

    logger.debug(s"token = $token")

    val request = ChatPostMessageRequest.builder
      .token(token)
      .username("meso-alert-slash-command")
      .channel(channelId)
      .text(message)
      .build

    logger.debug(s"Submitting request: $request")

    val chatPostMessageFuture = methods.chatPostMessage(request).asScala
    chatPostMessageFuture.onComplete {
      case Success(response) if response.isOk => logger.info(s"Successfully posted message $message to $channelId")
      case Success(response) if !response.isOk =>
        logger.error(response.getError)
        logger.error(response.toString)
      case Failure(ex) =>
        logger.error(s"Could not post message $message to $channelId: ${ex.getMessage}")
        ex.printStackTrace()
    }

    chatPostMessageFuture
  }

  override def receive: Receive = {
    case tx: TxUpdate =>
      logger.debug(s"Received $tx")
      sendMessage(channel.id, message(tx))
  }

  override def postStop(): Unit = {
    logger.debug("Terminating actor")
    super.postStop()
  }
}
