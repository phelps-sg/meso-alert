package services

import actors.{Register, Registered, Start, Started, Stop, Stopped}
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.ImplementedBy
import com.google.inject.name.Named
import dao.{Webhook, WebhookDao}
import org.slf4j.{Logger, LoggerFactory}

import java.net.URI
import javax.inject.{Inject, Singleton}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@ImplementedBy(classOf[WebhooksManager])
trait SlackWebhooksManagerService extends HooksManagerService[Webhook, URI]


@Singleton
class WebhooksManager @Inject()(memPoolWatcher: MemPoolWatcherService,
                                val webhookDao: WebhookDao,
                                @Named("webhooks-actor") val actor: ActorRef)
                               (implicit val system: ActorSystem, val executionContext: ExecutionContext)
  extends SlackWebhooksManagerService with HooksManager[URI, Webhook] {

  val logger: Logger = LoggerFactory.getLogger(classOf[WebhooksManager])

  def init(): Future[Seq[Started[Webhook]]] = {

    val initFuture = for {
      _ <- webhookDao.init()
      hooks <- webhookDao.all()
      started <- Future.sequence(hooks.map(hook => start(hook.uri)))
    } yield started

    initFuture.onComplete {
      case Success(x) => logger.info(f"Started ${x.size} hooks.")
      case Failure(exception) => logger.error(f"Failed to load hooks: ${exception.getMessage}")
    }

    initFuture
  }

}
