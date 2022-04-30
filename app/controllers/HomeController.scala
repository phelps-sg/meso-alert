package controllers

import actors.{TxFilterActor, TxUpdate}
import akka.actor.ActorSystem
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import play.api.Logger
import play.api.libs.json.Json
import play.api.libs.streams.ActorFlow
import play.api.mvc.WebSocket.MessageFlowTransformer
import play.api.mvc._
import services.{MemPoolWatcherService, SlackWebhooksManagerService, UserManagerService}

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(val controllerComponents: ControllerComponents,
                               val memPoolWatcher: MemPoolWatcherService,
                               val userManager: UserManagerService,
                               val slackWebHooksManager: SlackWebhooksManagerService)
                              (implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext)
  extends BaseController with SameOriginCheck {

  val logger: Logger = play.api.Logger(getClass)

  memPoolWatcher.start()
  slackWebHooksManager.start()

  implicit val mft: MessageFlowTransformer[TxFilterActor.Auth, TxUpdate] =
    MessageFlowTransformer.jsonMessageFlowTransformer[TxFilterActor.Auth, TxUpdate]

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

  def wsFutureFlow(request: RequestHeader): Future[Flow[TxFilterActor.Auth, TxUpdate, _]] = {
    Future {
      ActorFlow.actorRef[TxFilterActor.Auth, TxUpdate] {
        out => TxFilterActor.props(out, memPoolWatcher, userManager)
      }
    }
  }

  def websocket: WebSocket = WebSocket.acceptOrResult[TxFilterActor.Auth, TxUpdate] {
    case rh if sameOriginCheck(rh) =>
      wsFutureFlow(rh).map { flow =>
        Right(flow)
      }.recover {
        case e: Exception =>
          logger.error("Cannot create websocket", e)
          val jsError = Json.obj("error" -> "Cannot create websocket")
          val result = InternalServerError(jsError)
          Left(result)
      }

    case rejected =>
      logger.error(s"Request $rejected failed same origin check")
      Future.successful {
        Left(Forbidden("forbidden"))
      }
  }

}

trait SameOriginCheck {

  private val logger: Logger = play.api.Logger(getClass)

  /**
   * Checks that the WebSocket comes from the same origin.  This is necessary to protect
   * against Cross-Site WebSocket Hijacking as WebSocket does not implement Same Origin Policy.
   *
   * See https://tools.ietf.org/html/rfc6455#section-1.3 and
   * http://blog.dewhurstsecurity.com/2013/08/30/security-testing-html5-websockets.html
   */
  def sameOriginCheck(rh: RequestHeader): Boolean = {
    rh.headers.get("Origin") match {
      case Some(originValue) if originMatches(originValue) =>
        logger.debug(s"originCheck: originValue = $originValue")
        true

      case Some(badOrigin) =>
        logger.error(s"originCheck: rejecting request because Origin header value $badOrigin is not in the same origin")
        false

      case None =>
        logger.error("originCheck: rejecting request because no Origin header found")
        false
    }
  }

  /**
   * Returns true if the value of the Origin header contains an acceptable value.
   *
   * This is probably better done through configuration same as the allowedhosts filter.
   */
  def originMatches(origin: String): Boolean = {
    origin.contains("localhost:9000") || origin.contains("localhost:19001")
  }

}
