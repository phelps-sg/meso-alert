package controllers

import actors.AuthenticationActor._
import actors.{AuthenticationActor, TxUpdate}
import akka.actor.{ActorSystem, Props}
import akka.stream.Materializer
import akka.stream.scaladsl.Flow
import com.google.inject.ImplementedBy
import play.api.data.Form
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json.Json
import play.api.libs.streams.ActorFlow
import play.api.mvc.WebSocket.MessageFlowTransformer
import play.api.mvc._
import play.api.{Logger, Logging}
import play.api.data.Forms._
import courier._
import play.api.libs.concurrent.CustomExecutionContext

import scala.util._
import javax.inject._
import scala.concurrent.{ExecutionContext, Future}
import play.api.data.Mapping

import javax.mail.internet.InternetAddress

@ImplementedBy(classOf[EmailExecutionContextImpl])
trait EmailExecutionContext extends ExecutionContext

class EmailExecutionContextImpl @Inject() (system: ActorSystem)
  extends CustomExecutionContext(system, "email.dispatcher")
    with EmailExecutionContext

@Singleton
class HomeController @Inject()(val controllerComponents: ControllerComponents,
                               val actorFactory: AuthenticationActor.Factory,
                               val emailExecutionContext: EmailExecutionContext)
                              (implicit system: ActorSystem, mat: Materializer, implicit val ec: ExecutionContext)
  extends BaseController with SameOriginCheck with InjectedActorSupport with Logging {

  implicit val mft: MessageFlowTransformer[Auth, TxUpdate] =
    MessageFlowTransformer.jsonMessageFlowTransformer[Auth, TxUpdate]

  case class FeedbackFormData(name: String, email: String, message: String)

  val feedbackFormMapping: Mapping[FeedbackFormData] = mapping(
    "name" -> text,
    "email" -> email,
    "message" -> nonEmptyText
  )(FeedbackFormData.apply)(FeedbackFormData.unapply)

  val feedbackForm: Form[FeedbackFormData] = Form(feedbackFormMapping)
  // email configuration
  val emailHost = "meso_alert_tester@outlook.com"
  val mailer: Mailer = Mailer("smtp-mail.outlook.com", 587)
    .auth(true)
    .as(emailHost, "ficZeq-vutsoj-qypru5")
    .startTls(true)()

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

  def feedbackPage(): Action[AnyContent]= Action { implicit request =>
    Ok(views.html.feedback(""))
  }

  def create(): Action[AnyContent] = Action.async { implicit request =>
    feedbackForm.bindFromRequest.fold(
      _ => Future { BadRequest },
      feedbackData =>  {
        mailer(Envelope.from(new InternetAddress(emailHost))
          .to(new InternetAddress(emailHost))
          .subject("Feedback - " + feedbackData.name + " " + feedbackData.email)
          .content(Text(feedbackData.message)))(emailExecutionContext).map {
          _ =>
            logger.info("feedback email delivered")
            Ok(views.html.feedback("success"))
        }(emailExecutionContext) recover {
          er =>
            logger.error(er.getMessage)
            Ok(views.html.feedback("failed"))
          }
        }
    )}

  def wsFutureFlow(request: RequestHeader): Future[Flow[AuthenticationActor.Auth, TxUpdate, _]] = {
    Future {
      ActorFlow.actorRef[AuthenticationActor.Auth, TxUpdate] {
            out => Props(actorFactory(out))
//        out => TxFilterAuthActor.props(out, memPoolWatcher, userManager)
      }
    }
  }

  def websocket: WebSocket = WebSocket.acceptOrResult[AuthenticationActor.Auth, TxUpdate] {
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
