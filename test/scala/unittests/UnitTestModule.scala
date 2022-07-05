package unittests

import actors.{AuthenticationActor, TxFilterActor, TxMessagingActorSlackChat, TxMessagingActorWeb}
import com.google.inject.AbstractModule
import play.libs.akka.AkkaGuiceSupport
import slick.jdbc
import slick.jdbc.JdbcBackend.Database

import javax.inject.Provider
import scala.concurrent.ExecutionContext

class UnitTestModule(val db: jdbc.JdbcBackend.Database, val testExecutionContext: ExecutionContext)
  extends AbstractModule with AkkaGuiceSupport {
  override def configure(): Unit = {
    bind(classOf[Database]).toProvider(new Provider[Database] {
      val get: jdbc.JdbcBackend.Database = db
    })
    bind(classOf[ExecutionContext]).toInstance(testExecutionContext)
    //      bindActor(classOf[MemPoolWatcherActor], "mem-pool-actor")
    //      bindActor(classOf[WebhooksActor], "webhooks-actor")
    bindActorFactory(classOf[TxMessagingActorWeb], classOf[TxMessagingActorWeb.Factory])
    bindActorFactory(classOf[TxMessagingActorSlackChat], classOf[TxMessagingActorSlackChat.Factory])
    bindActorFactory(classOf[AuthenticationActor], classOf[AuthenticationActor.Factory])
    bindActorFactory(classOf[TxFilterActor], classOf[TxFilterActor.Factory])
  }
}