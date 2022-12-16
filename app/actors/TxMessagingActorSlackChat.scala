package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import actors.RateLimitingBatchingActor.TxBatch
import actors.TxMessagingActorSlackChat.MESSAGE_BOT_NAME
import akka.actor.{Actor, Timers}
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import dao.SlackChatHookPlainText
import play.api.i18n.{Lang, MessagesApi}
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

  val MESSAGE_BOT_NAME = "slackChat.botName"

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
) extends TxRetryOrDie[Blocks, TxBatch]
    with Timers
    with UnrecognizedMessageHandlerFatal
    with Logging {

  implicit val lang: Lang = Lang("en")
  implicit val ec: SlackChatExecutionContext = sce

  private val botName = messagesApi(MESSAGE_BOT_NAME)

  override val maxRetryCount: Int = 3
  override val backoffPolicyBase: FiniteDuration = 2000 milliseconds
  override val backoffPolicyCap: FiniteDuration = 20000 milliseconds
  override val backoffPolicyMin: FiniteDuration = 1500 milliseconds

  override def success(): Unit = logger.debug("Successfully posted message")

  private val toBlocks: TxUpdate => Blocks = BlockMessages.message(messagesApi)

  override def process(tx: TxBatch): Future[Blocks] = {

    val blocks = tx.messages.map(toBlocks) reduce { (x, y) =>
      Blocks(s"${x.value}\n${y.value}")
    }

    slackManagerService.chatPostMessage(
      hook.token,
      botName,
      hook.channel,
      "New Transaction",
      blocks
    )
  }

}
