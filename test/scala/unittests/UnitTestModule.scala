package unittests

import actors.{
  AuthenticationActor,
  TxFilterActor,
  TxMessagingActorSlackChat,
  TxMessagingActorWeb
}
import com.google.inject.AbstractModule
import play.api.i18n.MessagesApi
import play.libs.akka.AkkaGuiceSupport
import slick.jdbc
import slick.jdbc.JdbcBackend.Database

import javax.inject.Provider
import scala.concurrent.ExecutionContext

class UnitTestModule(
    val db: jdbc.JdbcBackend.Database,
    val testExecutionContext: ExecutionContext,
    val messagesApi: MessagesApi
) extends AbstractModule
    with AkkaGuiceSupport {
  override def configure(): Unit = {
    bind(classOf[Database]).toProvider(new Provider[Database] {
      val get: jdbc.JdbcBackend.Database = db
    })
    bind(classOf[MessagesApi]).toInstance(messagesApi)
    bind(classOf[scala.util.Random])
      .toProvider(new Provider[scala.util.Random] {
        val get: scala.util.Random = new scala.util.Random(1000)
      })
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
  }
}
