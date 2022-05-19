package services

import actors.WebhooksManagerActor.{Register, Registered, Start, Started, Stop, Stopped, WebhookNotRegisteredException}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.ImplementedBy
import com.google.inject.name.Named
import dao.{Webhook, WebhookDao}
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc.JdbcBackend.Database

import java.net.URI
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

@ImplementedBy(classOf[WebhooksManager])
trait SlackWebhooksManagerService {
  def init(): Future[Seq[Started]]
  def start(uri: URI): Future[Started]
  def stop(uri: URI): Future[Stopped]
  def register(hook: Webhook): Future[Registered]
}

@Singleton
class WebhooksManager @Inject()(memPoolWatcher: MemPoolWatcherService,
                                val webhookDao: WebhookDao,
                                @Named("webhooks-actor") actor: ActorRef)
                               (implicit system: ActorSystem, executionContext: ExecutionContext)
  extends SlackWebhooksManagerService {

  val logger: Logger = LoggerFactory.getLogger(classOf[WebhooksManager])

  implicit val timeout: Timeout = 1.minute

//  val webHooks: Array[Webhook] = Array(
//    Webhook(new URI("https://hooks.slack.com/services/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"),
//    threshold = 200000000000L),
//  )

  def init(): Future[Seq[Started]] = {

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

  def start(uri: URI): Future[Started] = {
    (actor ? Start(uri)).map {
      case x: Started => x
      case ex: WebhookNotRegisteredException => throw ex
    }
  }

  def stop(uri: URI): Future[Stopped] = {
    (actor ? Stop(uri)).map {
      case x: Stopped => x
    }
  }

  def register(hook: Webhook): Future[Registered] = {
    (actor ? Register(hook)).map {
      case x: Registered => x
    }
  }

}
