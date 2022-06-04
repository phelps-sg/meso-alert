package services

import actors.WebhooksManagerActor.{Register, Registered, Start, Started, Stop, Stopped, WebhookNotRegisteredException}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.util.Timeout
import com.google.inject.ImplementedBy
import com.google.inject.name.Named
import dao.{Webhook, WebhookDao}
import org.slf4j.{Logger, LoggerFactory}

import java.net.URI
import javax.inject.{Inject, Singleton}
import scala.concurrent.duration.DurationInt
import scala.concurrent.{ExecutionContext, Future}
import scala.reflect.ClassTag
import scala.util.{Failure, Success, Try}

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

  def sendAndReceive[T, R](message: T): Future[R] = {
    (actor ? message) map {
      case Success(x: R) => x
      case Failure(ex) => throw ex
    }
  }

  def start(uri: URI): Future[Started] = sendAndReceive(Start(uri))
  def stop(uri: URI): Future[Stopped] = sendAndReceive(Stop(uri))
  def register(hook: Webhook): Future[Registered] = sendAndReceive(Register(hook))

}
