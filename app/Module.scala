import actors.{AuthenticationActor, EncryptionActor, HooksManagerActorSlackChat, HooksManagerActorWeb, MemPoolWatcherActor, TxFilterActor, TxMessagingActorSlackChat, TxMessagingActorWeb, TxPersistenceActor}
import com.google.inject.AbstractModule
import com.typesafe.config.Config
import dao._
import play.libs.akka.AkkaGuiceSupport
import services.{EncryptionManagerService, HooksManagerSlackChat, HooksManagerSlackChatService, HooksManagerWeb, HooksManagerWebService, MemPoolWatcher, MemPoolWatcherService, SodiumEncryptionManager}
import slick.jdbc.JdbcBackend.Database

import javax.inject.{Inject, Provider, Singleton}
import scala.util.Random

class Module extends AbstractModule with AkkaGuiceSupport {

  protected def bindActors(): Unit = {
    bindActor(classOf[HooksManagerActorWeb], "webhooks-actor")
    bindActor(classOf[HooksManagerActorSlackChat], "slack-hooks-actor")
    bindActor(classOf[MemPoolWatcherActor], "mem-pool-actor")
    bindActor(classOf[EncryptionActor], "encryption-actor")
    bindActor(classOf[TxPersistenceActor], "tx-persistence-actor")

    bindActorFactory(classOf[TxMessagingActorWeb], classOf[TxMessagingActorWeb.Factory])
    bindActorFactory(classOf[TxMessagingActorSlackChat], classOf[TxMessagingActorSlackChat.Factory])
    bindActorFactory(classOf[AuthenticationActor], classOf[AuthenticationActor.Factory])
    bindActorFactory(classOf[TxFilterActor], classOf[TxFilterActor.Factory])
    bindActorFactory(classOf[TxPersistenceActor], classOf[TxPersistenceActor.Factory])
  }

  protected def bindDatabase(): Unit = {
    bind(classOf[Database]).toProvider(classOf[DatabaseProvider])
  }

  protected def bindPRNG(): Unit = {
    bind(classOf[scala.util.Random]).toProvider(classOf[RandomProvider])
  }

  protected def bindFutureInitialisingComponents(): Unit = {
    // Ensure all components that implement FutureInitialisingComponent are immediately initialised at startup
    bind(classOf[MemPoolWatcherService]).to(classOf[MemPoolWatcher]).asEagerSingleton()
    bind(classOf[HooksManagerWebService]).to(classOf[HooksManagerWeb]).asEagerSingleton()
    bind(classOf[HooksManagerSlackChatService]).to(classOf[HooksManagerSlackChat]).asEagerSingleton()
    bind(classOf[EncryptionManagerService]).to(classOf[SodiumEncryptionManager]).asEagerSingleton()
    bind(classOf[SlackTeamDao]).to(classOf[SlickSlackTeamDao]).asEagerSingleton()
    bind(classOf[SlackChatHookDao]).to(classOf[SlickSlackChatDao]).asEagerSingleton()
    bind(classOf[TransactionUpdateDao]).to(classOf[SlickTransactionUpdateDao]).asEagerSingleton()
  }

  override def configure(): Unit = {
    bindDatabase()
    bindPRNG()
    bindActors()
    bindFutureInitialisingComponents()
  }
}

@Singleton
class DatabaseProvider @Inject() (config: Config) extends Provider[Database] {
  lazy val get: Database = Database.forConfig("meso-alert.db", config)
}

@Singleton
class RandomProvider extends Provider[scala.util.Random] {
  lazy val get: Random.type = scala.util.Random
}
