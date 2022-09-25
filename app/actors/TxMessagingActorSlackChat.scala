package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import akka.actor.{Actor, Timers}
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import dao.SlackChatHookPlainText
import play.api.i18n.MessagesApi
import play.api.{Configuration, Logging}
import services.SlackManagerService
import slack.BlockMessages
import slick.SlackChatExecutionContext

import scala.annotation.unused
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.util.Random

object TxMessagingActorSlackChat {

  trait Factory extends TxMessagingActorFactory[SlackChatHookPlainText] {
    def apply(@unused hook: SlackChatHookPlainText): Actor
  }

}

class TxMessagingActorSlackChat @Inject() (
    protected val slackManagerService: SlackManagerService,
    protected val config: Configuration,
    sce: SlackChatExecutionContext,
    val random: Random,
    protected val messagesApi: MessagesApi,
    @Assisted hook: SlackChatHookPlainText
) extends Actor
    with TxRetryOrDie[ChatPostMessageResponse]
    with Timers
    with UnrecognizedMessageHandlerFatal
    with Logging {

  implicit val ec: SlackChatExecutionContext = sce

  override val maxRetryCount: Int = 3
  override val backoffPolicyBase: FiniteDuration = 2000 milliseconds
  override val backoffPolicyCap: FiniteDuration = 20000 milliseconds
  override val backoffPolicyMin: FiniteDuration = 1500 milliseconds

  override def success(): Unit = logger.debug("Successfully posted message")

  val message: TxUpdate => String = BlockMessages.message(messagesApi)

  override def process(tx: TxUpdate): Future[ChatPostMessageResponse] = {
    val msg = message(tx)
    val request = ChatPostMessageRequest.builder
      .token(hook.token)
      .username("block-insights")
      .channel(hook.channel.value)
      .text("New Transaction")
      .blocksAsString(msg)
      .build
    logger.debug(s"Submitting request: $request")
    slackManagerService.chatPostMessage(request)
  }
}
