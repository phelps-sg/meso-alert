package controllers

import akka.actor.ActorSystem
import com.google.inject.ImplementedBy
import play.api.data.Forms._
import play.api.data.{Form, Mapping}
import play.api.libs.concurrent.CustomExecutionContext
import play.api.mvc._
import play.api.{Configuration, Logging}
import services.MailManager

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[EmailExecutionContextImpl])
trait EmailExecutionContext extends ExecutionContext

class EmailExecutionContextImpl @Inject() (system: ActorSystem)
  extends CustomExecutionContext(system, "email.dispatcher")
    with EmailExecutionContext

@Singleton
class HomeController @Inject()(val controllerComponents: ControllerComponents,
                               protected val config: Configuration,
                               val mailManager: MailManager)
                              (implicit val ec: ExecutionContext)
  extends BaseController with Logging {

  case class FeedbackFormData(name: String, email: String, message: String)

  val feedbackFormMapping: Mapping[FeedbackFormData] = mapping(
    "name" -> text,
    "email" -> email,
    "message" -> nonEmptyText
  )(FeedbackFormData.apply)(FeedbackFormData.unapply)

  val feedbackForm: Form[FeedbackFormData] = Form(feedbackFormMapping)
  val slackDeployURL: String = config.get[String]("slack.deployURL")

  def index(): Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    Ok(views.html.index(slackDeployURL))
  }

  def feedbackPage(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.feedback(""))
  }

  def create(): Action[AnyContent] = Action.async { implicit request =>
    feedbackForm.bindFromRequest().fold(
      _ => Future {
        BadRequest
      },
      feedbackData => {
        mailManager.sendEmail("Feedback - " + feedbackData.name + " " + feedbackData.email,
          feedbackData.message).map {
          _ =>
            logger.info("feedback email delivered")
            Ok(views.html.feedback("success"))
        } recover {
          case ex: Exception =>
            logger.error(ex.getMessage)
            Ok(views.html.feedback("failed"))
        }
      }
    )
  }
}


