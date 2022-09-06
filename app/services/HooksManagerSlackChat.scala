package services

import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.ImplementedBy
import com.google.inject.name.Named
import dao.{SlackChannelId, SlackChatHook, SlackChatHookDao}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[HooksManagerSlackChat])
trait HooksManagerSlackChatService
    extends HooksManagerService[SlackChannelId, SlackChatHook]

@Singleton
class HooksManagerSlackChat @Inject() (
    val hookDao: SlackChatHookDao,
    @Named("slack-hooks-actor") val actor: ActorRef
)(implicit val system: ActorSystem, val executionContext: ExecutionContext)
    extends HooksManagerSlackChatService
    with HooksManager[SlackChannelId, SlackChatHook] {

  initialise()
}
