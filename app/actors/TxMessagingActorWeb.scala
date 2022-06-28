package actors

import akka.actor.Actor
import com.google.inject.assistedinject.Assisted
import com.google.inject.{ImplementedBy, Inject}
import monix.eval.Task
import org.apache.commons.logging.LogFactory
import play.api.libs.json.Json
import sttp.capabilities.WebSockets
import sttp.capabilities.monix.MonixStreams
import sttp.client3._
import sttp.client3.asynchttpclient.monix._
import sttp.model.Uri

import java.net.URI
import javax.inject.Singleton
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

  trait Factory extends TxMessagingActorFactory[URI] {
    def apply(hookUri: URI): Actor
  }
}

class TxMessagingActorWeb @Inject()(backendSelection: HttpBackendSelection, @Assisted hookUri: URI)  extends Actor {

  private val logger = LogFactory.getLog(classOf[TxMessagingActorWeb])

  override def receive: Receive = {
    case tx: TxUpdate =>

      val postTask = backendSelection.backend().flatMap { backend =>
        val r = basicRequest
          .contentType("application/json")
          .body(Json.stringify(Json.obj("text" -> message(tx))))
          .post(Uri(javaUri = hookUri))

        r.send(backend)
          .flatMap { response => Task(logger.debug(s"""Got ${response.code} response, body:\n${response.body}""")) }
          .guarantee(backend.close())
      }

      import monix.execution.Scheduler.Implicits.global
      val f = postTask.runToFuture

      f onComplete {
        case Success(_) => logger.debug("Successfully posted message")
        case Failure(ex) =>
          logger.error(ex.getMessage)
          ex.printStackTrace()
      }
  }

}
