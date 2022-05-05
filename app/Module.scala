import actors.{TxFilterAuthActor, TxFilterNoAuthActor, TxWebhookMessagingActor, WebhookManagerActor}
import com.google.inject.AbstractModule
import play.libs.akka.AkkaGuiceSupport

class Module extends AbstractModule with AkkaGuiceSupport {

  override def configure(): Unit = {
    bindActor(classOf[WebhookManagerActor], "webhooks-actor")
    bindActorFactory(classOf[TxWebhookMessagingActor], classOf[TxWebhookMessagingActor.Factory])
    bindActorFactory(classOf[TxFilterAuthActor], classOf[TxFilterAuthActor.Factory])
    bindActorFactory(classOf[TxFilterNoAuthActor], classOf[TxFilterNoAuthActor.Factory])
  }
}
