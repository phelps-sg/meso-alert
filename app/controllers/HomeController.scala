package controllers

import actors.TxFilterAuthActor._
import actors.{TxFilterAuthActor, TxUpdate}
import akka.actor.{ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import play.api.Logger
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json.Json
import play.api.libs.streams.ActorFlow
import play.api.mvc.WebSocket.MessageFlowTransformer
import play.api.mvc._
import services.{HooksManagerSlackChatService, HooksManagerWebService, MemPoolWatcherService, UserManagerService}

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * This controller creates an `Action` to handle HTTP requests to the
 * application's home page.
 */
@Singleton
class HomeController @Inject()(val controllerComponents: ControllerComponents,
                               val memPoolWatcher: MemPoolWatcherService,
                               val userManager: UserManagerService,
                               val webHooksManager: HooksManagerWebService,
                               val slackChatHooksManager: HooksManagerSlackChatService,
                               val actorFactory: TxFilterAuthActor.Factory)
                              (implicit system: ActorSystem, mat: Materializer, ec: ExecutionContext)
  extends BaseController with SameOriginCheck with InjectedActorSupport {

  val logger: Logger = play.api.Logger(getClass)

  private val init = for {
    _ <- memPoolWatcher.init()
    _ <- webHooksManager.init()
    _ <- slackChatHooksManager.init()
  } yield ()

  init.onComplete{
    case Success(_) => logger.info("Initialisation complete.")
    case Failure(ex) =>
      logger.error(s"Initialisation failed with ${ex.getMessage}")
      ex.printStackTrace()
  }

  implicit val mft: MessageFlowTransformer[Auth, TxUpdate] =
    MessageFlowTransformer.jsonMessageFlowTransformer[Auth, TxUpdate]

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

  def wsFutureFlow(request: RequestHeader): Future[Flow[TxFilterAuthActor.Auth, TxUpdate, _]] = {
    Future {
      ActorFlow.actorRef[TxFilterAuthActor.Auth, TxUpdate] {
            out => Props(actorFactory(out))
//        out => TxFilterAuthActor.props(out, memPoolWatcher, userManager)
      }
    }
  }

  def websocket: WebSocket = WebSocket.acceptOrResult[TxFilterAuthActor.Auth, TxUpdate] {
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
