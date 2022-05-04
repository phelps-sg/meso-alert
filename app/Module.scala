import actors.{TxSlackActor, WebhookManagerActor}
import com.google.inject.AbstractModule
import play.libs.akka.AkkaGuiceSupport

class Module extends AbstractModule with AkkaGuiceSupport {

  override def configure(): Unit = {
    bindActor(classOf[WebhookManagerActor], "webhooks-actor")
    bindActorFactory(classOf[TxSlackActor], classOf[TxSlackActor.Factory])
  }
}
