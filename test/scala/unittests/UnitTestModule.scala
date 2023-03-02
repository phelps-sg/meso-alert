package unittests

import actors.{
  AuthenticationActor,
  RateLimitingBatchingActor,
  TxFilterActor,
  TxMessagingActorSlackChat,
  TxMessagingActorWeb
}
import com.google.inject.AbstractModule
import play.api.i18n.MessagesApi
import play.libs.akka.AkkaGuiceSupport
import slick.jdbc
import slick.jdbc.JdbcBackend.Database

import java.time.Clock
import javax.inject.Provider
import scala.concurrent.ExecutionContext

class UnitTestModule(
    val db: jdbc.JdbcBackend.Database,
    val testExecutionContext: ExecutionContext,
    val messagesApi: MessagesApi,
    val clock: Clock
) extends AbstractModule
    with AkkaGuiceSupport {

  override def configure(): Unit = {
    bind(classOf[Database]).toProvider(new Provider[Database] {
      val get: jdbc.JdbcBackend.Database = db
    })
    bind(classOf[Clock]).toProvider(new Provider[Clock] {
      val get: Clock = clock
    })
    bind(classOf[MessagesApi]).toInstance(messagesApi)
    bind(classOf[ExecutionContext]).toInstance(testExecutionContext)
    bindActorFactory(
      classOf[TxMessagingActorWeb],
      classOf[TxMessagingActorWeb.Factory]
    )
    bindActorFactory(
      classOf[TxMessagingActorSlackChat],
      classOf[TxMessagingActorSlackChat.Factory]
    )
    bindActorFactory(
      classOf[AuthenticationActor],
      classOf[AuthenticationActor.Factory]
    )
    bindActorFactory(classOf[TxFilterActor], classOf[TxFilterActor.Factory])
    bindActorFactory(
      classOf[RateLimitingBatchingActor],
      classOf[RateLimitingBatchingActor.Factory]
    )
  }
}
