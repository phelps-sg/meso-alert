package slick

import akka.actor.ActorSystem
import com.google.inject.{Inject, Singleton}
import play.libs.concurrent.CustomExecutionContext

@Singleton
class DatabaseExecutionContext @Inject() (system: ActorSystem)
    extends CustomExecutionContext(system, "database.dispatcher") {}

@Singleton
class SlackChatExecutionContext @Inject() (system: ActorSystem)
    extends CustomExecutionContext(system, "slackChat.dispatcher")

@Singleton
class EncryptionExecutionContext @Inject() (system: ActorSystem)
    extends CustomExecutionContext(system, "encryption.dispatcher")
