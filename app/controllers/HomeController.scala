package controllers

import akka.actor.ActorSystem
import com.google.inject.ImplementedBy
import controllers.HomeController.FeedbackFormData
import play.api.data.Forms._
import play.api.data.{Form, Mapping}
import play.api.i18n.I18nSupport
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

object HomeController {
  case class FeedbackFormData(name: String, email: String, message: String)
}

@Singleton
class HomeController @Inject() (
    val controllerComponents: ControllerComponents,
    protected val config: Configuration,
    val mailManager: MailManager
)(implicit val ec: ExecutionContext)
    extends BaseController
    with Logging
    with I18nSupport {

  val feedbackFormMapping: Mapping[FeedbackFormData] = mapping(
    "name" -> text,
    "email" -> email,
    "message" -> nonEmptyText
  )(FeedbackFormData.apply)(FeedbackFormData.unapply)

  val feedbackForm: Form[FeedbackFormData] = Form(feedbackFormMapping)
  val slackDeployURL: String = config.get[String]("slack.deployURL")

  def index(): Action[AnyContent] = Action {
    implicit request: Request[AnyContent] =>
      Ok(views.html.index(slackDeployURL))
  }

  def feedbackPage(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.feedback("", feedbackForm))
  }

  def privacyPolicy(): Action[AnyContent] = Action { _ =>
    Ok(views.html.privacy_policy())
  }

  def create(): Action[AnyContent] = Action.async { implicit request =>
    feedbackForm
      .bindFromRequest()
      .fold(
        _ =>
          Future {
            BadRequest
          },
        feedbackData => {
          mailManager
            .sendEmail(
              "Feedback - " + feedbackData.name + " " + feedbackData.email,
              feedbackData.message
            )
            .map { _ =>
              logger.info("feedback email delivered")
              Ok(views.html.feedback("success", feedbackForm))
            } recover { case ex: Exception =>
            logger.error(ex.getMessage)
            ex.printStackTrace()
            Ok(
              views.html.feedback(
                "failed",
                feedbackForm.fill(
                  FeedbackFormData(
                    feedbackData.name,
                    feedbackData.email,
                    feedbackData.message
                  )
                )
              )
            )
          }
        }
      )
  }
}
