package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import akka.actor.{Actor, Timers}
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import dao.SlackChatHookPlainText
import play.api.i18n.MessagesApi
import play.api.{Configuration, Logging}
import services.SlackManagerService
import slack.BlockMessages
import slack.BlockMessages.Blocks
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
    with TxRetryOrDie[Blocks]
    with Timers
    with UnrecognizedMessageHandlerFatal
    with Logging {

  implicit val ec: SlackChatExecutionContext = sce

  override val maxRetryCount: Int = 3
  override val backoffPolicyBase: FiniteDuration = 2000 milliseconds
  override val backoffPolicyCap: FiniteDuration = 20000 milliseconds
  override val backoffPolicyMin: FiniteDuration = 1500 milliseconds

  override def success(): Unit = logger.debug("Successfully posted message")

  val message: TxUpdate => Blocks = BlockMessages.message(messagesApi)

  override def process(tx: TxUpdate): Future[Blocks] = {
    val msg = message(tx)
    slackManagerService.chatPostMessage(
      hook.token,
      "block-insights",
      hook.channel,
      "New Transaction",
      msg
    )
  }
}
