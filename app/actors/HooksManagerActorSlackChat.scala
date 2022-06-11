package actors

import akka.actor.Props
import com.google.inject.Inject
import dao.{SlackChannel, SlackChatHook, SlackChatHookDao}
import org.slf4j.{Logger, LoggerFactory}
import slick.DatabaseExecutionContext

import java.net.URLEncoder

object HooksManagerActorSlackChat {

  def props(messagingActorFactory: TxMessagingActorSlackChat.Factory,
            filteringActorFactory: TxFilterNoAuthActor.Factory,
            slackChatHookDao: SlackChatHookDao,
            databaseExecutionContext: DatabaseExecutionContext): Props =
    Props(new HooksManagerActorSlackChat(messagingActorFactory, filteringActorFactory, slackChatHookDao, databaseExecutionContext))

//  implicit val startWrites: Writes[Started[Webhook]] = new Writes[Started[Webhook]]() {
//    def writes(started: Started[Webhook]): JsObject = Json.obj(fields =
//      "uri" -> started.hook.uri,
//      "threshold" -> started.hook.threshold
//    )
//  }
}

class HooksManagerActorSlackChat @Inject()(val messagingActorFactory: TxMessagingActorSlackChat.Factory,
                                           val filteringActorFactory: TxFilterNoAuthActor.Factory,
                                           val dao: SlackChatHookDao,
                                           val databaseExecutionContext: DatabaseExecutionContext)
  extends HooksManagerActor[SlackChannel, SlackChatHook] {

  override val logger: Logger = LoggerFactory.getLogger(classOf[HooksManagerActorSlackChat])
  override val hookTypePrefix: String = "slack-chat-hook"
  override def encodeKey(channel: SlackChannel): String = URLEncoder.encode(channel.id, "UTF-8")
}