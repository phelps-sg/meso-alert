package services

import com.google.inject.{ImplementedBy, Inject}
import monix.eval.Task
import play.api.Logging
import play.api.libs.json.{JsObject, Json}
import sttp.capabilities.WebSockets
import sttp.capabilities.monix.MonixStreams
import sttp.client3.asynchttpclient.monix.AsyncHttpClientMonixBackend
import sttp.client3.{SttpBackend, basicRequest}
import sttp.model.{StatusCode, Uri}

import javax.inject.Singleton
import scala.concurrent.Future

@ImplementedBy(classOf[MonixBackend])
trait HttpBackendSelection {
  def backend(): Task[SttpBackend[Task, _]]
}

@Singleton
class MonixBackend extends HttpBackendSelection {
  def backend(): Task[SttpBackend[Task, MonixStreams with WebSockets]] =
    AsyncHttpClientMonixBackend()
}

@ImplementedBy(classOf[MonixWebManager])
trait WebManagerService {

  def postJson(content: JsObject, uri: Uri): Future[StatusCode]
}

class MonixWebManager @Inject() (val backendSelection: HttpBackendSelection)
    extends WebManagerService
    with Logging {

  override def postJson(jsObject: JsObject, uri: Uri): Future[StatusCode] = {

    val postTask = backendSelection.backend().flatMap { backend =>
      val r = basicRequest
        .contentType("application/json")
        .body(Json.stringify(jsObject))
        .post(uri)

      r.send(backend)
        .flatMap { response =>
          Task {
            logger.debug(
              s"""Got ${response.code} response, body:\n${response.body}"""
            )
            response.code
          }
        }
        .guarantee(backend.close())
    }

    import monix.execution.Scheduler.Implicits.global

    postTask.runToFuture
  }

}
