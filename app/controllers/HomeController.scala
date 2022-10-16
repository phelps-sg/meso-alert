package controllers

import akka.actor.ActorSystem
import com.google.inject.ImplementedBy
import controllers.HomeController.EmailFormData
import play.api.data.Forms._
import play.api.data.{Form, Mapping}
import play.api.i18n.I18nSupport
import play.api.libs.concurrent.CustomExecutionContext
import play.api.mvc._
import play.api.{Configuration, Logging}
import services.MailManager

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

object EmailFormType extends Enumeration {
  type EmailFormType = Value
  val feedback: Value = Value("Feedback")
  val support: Value = Value("Support")
}

@ImplementedBy(classOf[EmailExecutionContextImpl])
trait EmailExecutionContext extends ExecutionContext

class EmailExecutionContextImpl @Inject() (system: ActorSystem)
    extends CustomExecutionContext(system, "email.dispatcher")
    with EmailExecutionContext

object HomeController {
  import EmailFormType._
  case class EmailFormData(
      formType: EmailFormType,
      name: String,
      email: String,
      message: String
  ) {}
  object EmailFormData {
    def apply(
        formType: String,
        name: String,
        email: String,
        message: String
    ): EmailFormData =
      apply(EmailFormType.withName(formType), name, email, message)
    def unapplyToStrings(
        form: EmailFormData
    ): Option[(String, String, String, String)] =
      Some(form.formType.toString, form.name, form.email, form.message)
  }

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

  import EmailFormType._

  protected val emailDestinationFeedback: String =
    config.get[String]("email.destination")
  protected val emailDestinationSupport: String =
    config.get[String]("email.destinationSupport")

  val emailFormMapping: Mapping[EmailFormData] = mapping(
    "formType" -> nonEmptyText,
    "name" -> text,
    "email" -> email,
    "message" -> nonEmptyText
  )(EmailFormData.apply)(EmailFormData.unapplyToStrings)

  val emailForm: Form[EmailFormData] = Form(emailFormMapping)
  val feedbackForm: Form[EmailFormData] =
    emailForm.fill(EmailFormData(EmailFormType.feedback, "", "", ""))
  val supportForm: Form[EmailFormData] =
    emailForm.fill(EmailFormData(EmailFormType.support, "", "", ""))
  val slackDeployURL: String = config.get[String]("slack.deployURL")

  def index(): Action[AnyContent] = Action {
    implicit request: Request[AnyContent] =>
      Ok(views.html.index(slackDeployURL))
  }

  def feedbackPage(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.feedback("", feedbackForm))
  }

  def supportPage(): Action[AnyContent] = Action { implicit request =>
    Ok(views.html.support("", supportForm))
  }

  def privacyPolicy(): Action[AnyContent] = Action { _ =>
    Ok(views.html.privacy_policy())
  }

  def websiteDisclaimer(): Action[AnyContent] = Action { _ =>
    Ok(views.html.website_disclaimer())
  }

  def termsAndConditions(): Action[AnyContent] = Action { _ =>
    Ok(views.html.terms_and_conditions())
  }

  def emailDestination(formType: EmailFormType): String =
    formType match {
      case EmailFormType.support  => emailDestinationSupport
      case EmailFormType.feedback => emailDestinationFeedback
    }

  def formView(
      formType: EmailFormType,
      status: String,
      supportForm: Form[EmailFormData],
      feedbackForm: Form[EmailFormData]
  )(implicit request: Request[AnyContent]) = {
    formType match {
      case EmailFormType.support =>
        views.html.support(status, supportForm)
      case EmailFormType.feedback =>
        views.html.feedback(status, feedbackForm)
    }
  }

  def sendEmail(
      formType: EmailFormType
  )(implicit request: Request[AnyContent]): Future[Result] = {
    emailForm
      .bindFromRequest()
      .fold(
        _ =>
          Future {
            BadRequest
          },
        formData => {
          val subject = s"$formType - ${formData.name} ${formData.email}"
          mailManager
            .sendEmail(emailDestination(formType), subject, formData.email)
            .map { _ =>
              logger.info("email delivered")
              Ok(
                formView(
                  formType,
                  status = "success",
                  supportForm = supportForm,
                  feedbackForm = feedbackForm
                )
              )
            } recover { case ex: Exception =>
            logger.error(ex.getMessage)
            ex.printStackTrace()
            val formDataFill = EmailFormData(
              formType,
              formData.name,
              formData.email,
              formData.message
            )
            val filledForm = emailForm.fill(formDataFill)
            Ok(
              formView(
                formType,
                status = "failed",
                supportForm = filledForm,
                feedbackForm = filledForm
              )
            )
          }
        }
      )
  }

  def postEmailForm(): Action[AnyContent] = Action.async { implicit request =>
    logger.info(request.body.toString)
    val formType: EmailFormType = emailForm
      .bindFromRequest()
      .fold(
        _ => throw new Exception("Error parsing form"),
        formData => formData.formType
      )
    sendEmail(formType)
  }
}
