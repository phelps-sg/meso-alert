package controllers

import actors.AuthenticationActor.Auth
import actors.{AuthenticationActor, TxUpdate}
import akka.actor.{ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json.Json
import play.api.libs.streams.ActorFlow
import play.api.mvc.WebSocket.MessageFlowTransformer
import play.api.mvc.{BaseController, ControllerComponents, RequestHeader, WebSocket}
import play.api.{Logger, Logging}

import javax.inject.Inject
import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Left, Right}

class WebsocketController @Inject() (
    val controllerComponents: ControllerComponents,
    val actorFactory: AuthenticationActor.Factory
)(
    implicit system: ActorSystem,
    mat: Materializer,
    implicit val ec: ExecutionContext
) extends BaseController
    with InjectedActorSupport
    with SameOriginCheck
    with Logging {

  implicit val mft: MessageFlowTransformer[Auth, TxUpdate] =
    MessageFlowTransformer.jsonMessageFlowTransformer[Auth, TxUpdate]

  def websocket: WebSocket =
    WebSocket.acceptOrResult[AuthenticationActor.Auth, TxUpdate] {
      case rh if sameOriginCheck(rh) =>
        wsFutureFlow(rh)
          .map { flow =>
            Right(flow)
          }
          .recover { case e: Exception =>
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

  def wsFutureFlow(
      @unused request: RequestHeader
  ): Future[Flow[AuthenticationActor.Auth, TxUpdate, _]] = {
    Future {
      ActorFlow.actorRef[AuthenticationActor.Auth, TxUpdate] { out =>
        Props(actorFactory(out))
      //        out => TxFilterAuthActor.props(out, memPoolWatcher, userManager)
      }
    }
  }
}

trait SameOriginCheck {

  private val logger: Logger = play.api.Logger(getClass)

  /** Checks that the WebSocket comes from the same origin. This is necessary to
    * protect against Cross-Site WebSocket Hijacking as WebSocket does not
    * implement Same Origin Policy.
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
        logger.error(
          s"originCheck: rejecting request because Origin header value $badOrigin is not in the same origin"
        )
        false

      case None =>
        logger.error(
          "originCheck: rejecting request because no Origin header found"
        )
        false
    }
  }

  /** Returns true if the value of the Origin header contains an acceptable
    * value.
    *
    * This is probably better done through configuration same as the
    * allowedhosts filter.
    */
  def originMatches(origin: String): Boolean = {
    origin.contains("localhost:9000") || origin.contains("localhost:19001")
  }
}
