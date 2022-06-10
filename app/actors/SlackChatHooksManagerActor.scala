package actors

import akka.actor.Props
import com.google.inject.Inject
import dao.{SlackChannel, SlackChatHook, SlackChatHookDao}
import slick.DatabaseExecutionContext

import java.net.URLEncoder

object SlackChatHooksManagerActor {

  def props(messagingActorFactory: TxMessagingActorSlackChat.Factory,
            filteringActorFactory: TxFilterNoAuthActor.Factory,
            slackChatHookDao: SlackChatHookDao,
            databaseExecutionContext: DatabaseExecutionContext): Props =
    Props(new SlackChatHooksManagerActor(messagingActorFactory, filteringActorFactory, slackChatHookDao, databaseExecutionContext))

//  implicit val startWrites: Writes[Started[Webhook]] = new Writes[Started[Webhook]]() {
//    def writes(started: Started[Webhook]): JsObject = Json.obj(fields =
//      "uri" -> started.hook.uri,
//      "threshold" -> started.hook.threshold
//    )
//  }
}

class SlackChatHooksManagerActor @Inject()(val messagingActorFactory: TxMessagingActorSlackChat.Factory,
                                      val filteringActorFactory: TxFilterNoAuthActor.Factory,
                                      val dao: SlackChatHookDao,
                                      val databaseExecutionContext: DatabaseExecutionContext)
  extends HooksManagerActor[SlackChannel, SlackChatHook] {

  override def encodeKey(channel: SlackChannel): String = URLEncoder.encode(channel.id, "UTF-8")
  override def hookTypePrefix: String = "webhook"
}
