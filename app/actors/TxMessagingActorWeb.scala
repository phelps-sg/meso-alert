package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import akka.actor.Actor
import com.google.inject.assistedinject.Assisted
import com.google.inject.{ImplementedBy, Inject}
import dao.Webhook
import monix.eval.Task
import play.api.Logging
import play.api.libs.json.Json
import sttp.capabilities.WebSockets
import sttp.capabilities.monix.MonixStreams
import sttp.client3._
import sttp.client3.asynchttpclient.monix._
import sttp.model.Uri

import javax.inject.Singleton
import scala.concurrent.Future
import scala.util.{Failure, Success}

@ImplementedBy(classOf[MonixBackend])
trait HttpBackendSelection {
  def backend(): Task[SttpBackend[Task, _]]
}

@Singleton
class MonixBackend extends HttpBackendSelection {
  def backend(): Task[SttpBackend[Task, MonixStreams with WebSockets]] = AsyncHttpClientMonixBackend()
}

object TxMessagingActorWeb {

  trait Factory extends TxMessagingActorFactory[Webhook] {
    def apply(hook: Webhook): Actor
  }
}

class TxMessagingActorWeb @Inject()(backendSelection: HttpBackendSelection, @Assisted hook: Webhook)
  extends Actor with UnrecognizedMessageHandlerFatal with Logging {

  def process(tx: TxUpdate): Future[Unit] = {

    val postTask = backendSelection.backend().flatMap { backend =>
      val r = basicRequest
        .contentType("application/json")
        .body(Json.stringify(Json.obj("text" -> message(tx))))
        .post(Uri(javaUri = hook.uri))

      r.send(backend)
        .flatMap { response => Task(logger.debug(s"""Got ${response.code} response, body:\n${response.body}""")) }
        .guarantee(backend.close())
    }

    import monix.execution.Scheduler.Implicits.global
    val result = postTask.runToFuture

    result onComplete {
      case Success(_) => logger.debug("Successfully posted message")
      case Failure(ex) =>
        logger.error(ex.getMessage)
        ex.printStackTrace()
    }

    result
  }

  override def receive: Receive = {
    case tx: TxUpdate => process(tx)
    case x => unrecognizedMessage(x)
  }

}
