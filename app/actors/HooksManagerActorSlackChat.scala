package actors

import akka.actor.Props
import com.google.inject.Inject
import dao.{SlackChannelId, SlackChatHook, SlackChatHookDao}
import slick.DatabaseExecutionContext

import java.net.URLEncoder

object HooksManagerActorSlackChat {

  def props(
      messagingActorFactory: TxMessagingActorSlackChat.Factory,
      filteringActorFactory: TxFilterActor.Factory,
      slackChatHookDao: SlackChatHookDao,
      databaseExecutionContext: DatabaseExecutionContext
  ): Props =
    Props(
      new HooksManagerActorSlackChat(
        messagingActorFactory,
        filteringActorFactory,
        slackChatHookDao,
        databaseExecutionContext
      )
    )

}

class HooksManagerActorSlackChat @Inject() (
    val messagingActorFactory: TxMessagingActorSlackChat.Factory,
    val filteringActorFactory: TxFilterActor.Factory,
    val dao: SlackChatHookDao,
    val databaseExecutionContext: DatabaseExecutionContext
) extends HooksManagerActor[SlackChannelId, SlackChatHook] {

  override val hookTypePrefix: String = "slack-chat-hook"
  override def encodeKey(channel: SlackChannelId): String =
    URLEncoder.encode(channel.value, "UTF-8")
}
