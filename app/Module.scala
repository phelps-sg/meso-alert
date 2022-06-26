import actors.{HooksManagerActorSlackChat, HooksManagerActorWeb, MemPoolWatcherActor, TxAuthActor, TxFilterNoAuthActor, TxMessagingActorSlackChat, TxMessagingActorWeb}
import com.google.inject.AbstractModule
import com.typesafe.config.Config
import play.libs.akka.AkkaGuiceSupport
import slick.jdbc.JdbcBackend.Database

import javax.inject.{Inject, Provider, Singleton}

class Module extends AbstractModule with AkkaGuiceSupport {

  override def configure(): Unit = {
    bind(classOf[Database]).toProvider(classOf[DatabaseProvider])
    bindActor(classOf[HooksManagerActorWeb], "webhooks-actor")
    bindActor(classOf[HooksManagerActorSlackChat], "slack-hooks-actor")
    bindActor(classOf[MemPoolWatcherActor], "mem-pool-actor")
    bindActorFactory(classOf[TxMessagingActorWeb], classOf[TxMessagingActorWeb.Factory])
    bindActorFactory(classOf[TxMessagingActorSlackChat], classOf[TxMessagingActorSlackChat.Factory])
    bindActorFactory(classOf[TxAuthActor], classOf[TxAuthActor.Factory])
    bindActorFactory(classOf[TxFilterNoAuthActor], classOf[TxFilterNoAuthActor.Factory])
  }
}

@Singleton
class DatabaseProvider @Inject() (config: Config) extends Provider[Database] {
  lazy val get = Database.forConfig("meso-alert.db", config)
}
