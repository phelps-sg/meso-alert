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
  def init(): Future[Seq[Try[Started]]]
  def start(uri: URI): Future[Try[Started]]
  def stop(uri: URI): Future[Try[Stopped]]
  def register(hook: Webhook): Future[Try[Registered]]
}

@Singleton
class WebhooksManager @Inject()(memPoolWatcher: MemPoolWatcherService,
                                val webhookDao: WebhookDao,
                                @Named("webhooks-actor") actor: ActorRef)
                               (implicit system: ActorSystem, executionContext: ExecutionContext)
  extends SlackWebhooksManagerService {

  val logger: Logger = LoggerFactory.getLogger(classOf[WebhooksManager])

  implicit val timeout: Timeout = 1.minute

  def init(): Future[Seq[Try[Started]]] = {

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

  def sendAndReceive[A, B: ClassTag](message: A): Future[Try[B]] = {
    (actor ? message).map {
      case x: Try[B] => x
      case x =>
        throw new RuntimeException(s"Unexpected type in response from actor: $x")
    }
  }

  def start(uri: URI): Future[Try[Started]] = {
    sendAndReceive(Start(uri))
  }

  def stop(uri: URI): Future[Try[Stopped]] = {
    sendAndReceive(Stop(uri))
  }

  def register(hook: Webhook): Future[Try[Registered]] = {
    sendAndReceive(Register(hook))
  }

}
