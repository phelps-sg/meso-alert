import actors.{AuthenticationActor, EncryptionActor, HooksManagerActorSlackChat, HooksManagerActorWeb, MemPoolWatcherActor, TxFilterActor, TxMessagingActorSlackChat, TxMessagingActorWeb, TxPersistenceActor}
import com.google.inject.AbstractModule
import com.typesafe.config.Config
import dao._
import play.libs.akka.AkkaGuiceSupport
import services.{EncryptionManagerService, HooksManagerSlackChat, HooksManagerSlackChatService, HooksManagerWeb, HooksManagerWebService, MemPoolWatcher, MemPoolWatcherService, SlickTxManager, SlickTxManagerService, SodiumEncryptionManager}
import slick.jdbc.JdbcBackend.Database

import javax.inject.{Inject, Provider, Singleton}

class Module extends AbstractModule with AkkaGuiceSupport {

  override def configure(): Unit = {

    bind(classOf[Database]).toProvider(classOf[DatabaseProvider])

    bindActor(classOf[HooksManagerActorWeb], "webhooks-actor")
    bindActor(classOf[HooksManagerActorSlackChat], "slack-hooks-actor")
    bindActor(classOf[MemPoolWatcherActor], "mem-pool-actor")
    bindActor(classOf[EncryptionActor], "encryption-actor")

    bindActorFactory(classOf[TxMessagingActorWeb], classOf[TxMessagingActorWeb.Factory])
    bindActorFactory(classOf[TxMessagingActorSlackChat], classOf[TxMessagingActorSlackChat.Factory])
    bindActorFactory(classOf[AuthenticationActor], classOf[AuthenticationActor.Factory])
    bindActorFactory(classOf[TxFilterActor], classOf[TxFilterActor.Factory])
    bindActorFactory(classOf[TxPersistenceActor], classOf[TxPersistenceActor.Factory])

    bind(classOf[MemPoolWatcherService]).to(classOf[MemPoolWatcher]).asEagerSingleton()
    bind(classOf[HooksManagerWebService]).to(classOf[HooksManagerWeb]).asEagerSingleton()
    bind(classOf[HooksManagerSlackChatService]).to(classOf[HooksManagerSlackChat]).asEagerSingleton()
    bind(classOf[EncryptionManagerService]).to(classOf[SodiumEncryptionManager]).asEagerSingleton()
    bind(classOf[SlickTxManagerService]).to(classOf[SlickTxManager]).asEagerSingleton()
    bind(classOf[SlackTeamDao]).to(classOf[SlickSlackTeamDao]).asEagerSingleton()
    bind(classOf[SlackChatHookDao]).to(classOf[SlickSlackChatDao]).asEagerSingleton()
    bind(classOf[TransactionUpdateDao]).to(classOf[SlickTransactionUpdateDao]).asEagerSingleton()
  }
}

@Singleton
class DatabaseProvider @Inject() (config: Config) extends Provider[Database] {
  lazy val get: Database = Database.forConfig("meso-alert.db", config)
}
