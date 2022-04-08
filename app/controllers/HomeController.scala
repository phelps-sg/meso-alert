package controllers

import actors.ValueWatchActor
import akka.actor.ActorSystem
import akka.stream.Materializer
import play.api.libs.json.JsValue
import play.api.libs.streams.ActorFlow
import play.api.mvc.WebSocket.MessageFlowTransformer
import play.api.mvc._

import javax.inject._

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(val controllerComponents: ControllerComponents)
                              (implicit system: ActorSystem, mat: Materializer) extends BaseController {

  implicit val messageFlowTransformer: MessageFlowTransformer[String, ValueWatchActor.TxUpdate] =
    MessageFlowTransformer.jsonMessageFlowTransformer[String, ValueWatchActor.TxUpdate]

  /**
   * Create an Action to render an HTML page.
   *
   * The configuration in the `routes` file means that this method
   * will be called when the application receives a `GET` request with
   * a path of `/`.
   */
  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index())
  }

  def socket: WebSocket = WebSocket.accept[String, ValueWatchActor.TxUpdate] { _ =>
    ActorFlow.actorRef { out =>
      ValueWatchActor.props(out)
    }
  }

}
