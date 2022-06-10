package services

import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.ImplementedBy
import com.google.inject.name.Named
import dao.{SlackChannel, SlackChatHook, SlackChatHookDao}

import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[SlackChatHooksManager])
trait SlackChatHooksManagerService extends HooksManagerService[SlackChannel, SlackChatHook]

@Singleton
class SlackChatHooksManager @Inject()(val hookDao: SlackChatHookDao,
                                @Named("slack-hooks-actor") val actor: ActorRef)
                               (implicit val system: ActorSystem, val executionContext: ExecutionContext)
  extends SlackChatHooksManagerService with HooksManager[SlackChannel, SlackChatHook]
