package actors

import akka.actor.Props
import com.google.inject.Inject
import dao.{SlackChannelId, SlackChatHookDao, SlackChatHookPlainText}
import slick.DatabaseExecutionContext

import java.net.URLEncoder
import java.time.Clock

object HooksManagerActorSlackChat {

  def props(
      messagingActorFactory: TxMessagingActorSlackChat.Factory,
      filteringActorFactory: TxFilterActor.Factory,
      batchingActorFactory: RateLimitingBatchingActor.Factory,
      slackChatHookDao: SlackChatHookDao,
      databaseExecutionContext: DatabaseExecutionContext,
      clock: Clock
  ): Props =
    Props(
      new HooksManagerActorSlackChat(
        messagingActorFactory,
        filteringActorFactory,
        batchingActorFactory,
        slackChatHookDao,
        databaseExecutionContext,
        clock
      )
    )

}

class HooksManagerActorSlackChat @Inject() (
    val messagingActorFactory: TxMessagingActorSlackChat.Factory,
    val filteringActorFactory: TxFilterActor.Factory,
    val batchingActorFactory: RateLimitingBatchingActor.Factory,
    val dao: SlackChatHookDao,
    val databaseExecutionContext: DatabaseExecutionContext,
    val clock: Clock
) extends HooksManagerActor[SlackChannelId, SlackChatHookPlainText] {

  override val hookTypePrefix: String = "slack-chat-hook"
  override def encodeKey(channel: SlackChannelId): String =
    URLEncoder.encode(channel.value, "UTF-8")
}
