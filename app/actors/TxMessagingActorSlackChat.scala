package actors

import actors.RateLimitingBatchingActor.TxBatch
import actors.TxMessagingActorSlackChat.MESSAGE_BOT_NAME
import akka.actor.Actor
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import dao.SlackChatHookPlainText
import play.api.Configuration
import play.api.i18n.{Lang, MessagesApi}
import services.SlackManagerService
import slack.BlockMessages
import slack.BlockMessages.BlockMessage
import slick.SlackChatExecutionContext

import scala.annotation.unused
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
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
) extends RetryOrDieActor[BlockMessage, TxBatch] {

  implicit val lang: Lang = Lang("en")
  implicit val ec: SlackChatExecutionContext = sce

  private val botName = messagesApi(MESSAGE_BOT_NAME)

  override val maxRetryCount: Int = 3
  override val backoffPolicyBase: FiniteDuration = 2.seconds
  override val backoffPolicyCap: FiniteDuration = 20.seconds
  override val backoffPolicyMin: FiniteDuration = 1500.milliseconds

  private val toBlockMessage: TxBatch => BlockMessage =
    BlockMessages.txBatchToBlockMessage(messagesApi)

  override def process(batch: TxBatch): Future[BlockMessage] = {
    val blockMessage = toBlockMessage(batch)
    logger.debug(s"Posting chat message: $blockMessage")
    slackManagerService.chatPostMessage(
      hook.token,
      botName,
      hook.channel,
      "New Transaction",
      blockMessage
    )
  }

}
