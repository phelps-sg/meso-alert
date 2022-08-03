package services

import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.ImplementedBy
import com.google.inject.name.Named
import dao.{Webhook, WebhookDao}

import java.net.URI
import javax.inject.{Inject, Singleton}
import scala.concurrent.ExecutionContext

@ImplementedBy(classOf[HooksManagerWeb])
trait HooksManagerWebService extends HooksManagerService[URI, Webhook]

@Singleton
class HooksManagerWeb @Inject() (
    val hookDao: WebhookDao,
    @Named("webhooks-actor") val actor: ActorRef
)(implicit val system: ActorSystem, val executionContext: ExecutionContext)
    extends HooksManagerWebService
    with HooksManager[URI, Webhook] {

  initialise()
}
