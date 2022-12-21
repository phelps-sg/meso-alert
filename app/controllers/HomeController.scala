package controllers

import controllers.HomeController.EmailFormData
import play.api.data.Forms._
import play.api.data.{Form, Mapping}
import play.api.i18n.I18nSupport
import play.api.mvc._
import play.api.{Configuration, Logging}
import play.twirl.api.Html
import services.MailManager

import javax.inject._
import scala.concurrent.{ExecutionContext, Future}

object EmailFormType extends Enumeration {
  type EmailFormType = Value
  val feedback: Value = Value("Feedback")
  val support: Value = Value("Support")
}

object HomeController {

  import EmailFormType._

  case class EmailFormData(
      formType: EmailFormType,
      name: String,
      email: String,
      message: String
  ) {
    def subjectLine: String = s"$formType - $name $email"
  }

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

  abstract class FormEmailManager(val emailForm: Form[EmailFormData])(implicit
      val request: Request[AnyContent]
  ) {
    def destination: String

    val formType: EmailFormType

    def formView(status: String, form: Form[EmailFormData]): Html

    def sendEmail(): Future[Result] = {
      emailForm
        .bindFromRequest()
        .fold(
          _ =>
            Future {
              BadRequest
            },
          formData => {
            mailManager
              .sendEmail(destination, formData.subjectLine, formData.message)
              .map { _ =>
                logger.info("email delivered")
                Ok(
                  formView(status = "success", emailForm)
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
                formView(status = "failed", filledForm)
              )
            }
          }
        )
    }
  }

  class SupportFormEmailManager(implicit
      request: Request[AnyContent]
  ) extends FormEmailManager(supportForm)(request) {
    override def destination: String =
      config.get[String]("email.destinationSupport")
    override val formType: EmailFormType = EmailFormType.support
    override def formView(status: String, form: Form[EmailFormData]): Html =
      views.html.support(status, form)
  }

  class FeedbackFormEmailManager(implicit
      request: Request[AnyContent]
  ) extends FormEmailManager(feedbackForm)(request) {
    override def destination: String =
      config.get[String]("email.destination")
    override val formType: EmailFormType = EmailFormType.feedback
    override def formView(status: String, form: Form[EmailFormData]): Html =
      views.html.feedback(status, form)
  }

  object FormEmailManager {
    def apply(formType: EmailFormType)(implicit
        request: Request[AnyContent]
    ): FormEmailManager = formType match {
      case EmailFormType.support =>
        new SupportFormEmailManager()(request)
      case EmailFormType.feedback =>
        new FeedbackFormEmailManager()(request)
    }
  }

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
    // noinspection ScalaUnusedSymbol
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

  def postEmailForm(): Action[AnyContent] = Action.async { implicit request =>
    logger.info(request.body.toString)
    val formType: EmailFormType = emailForm
      .bindFromRequest()
      .fold(
        _ => throw new Exception("Error parsing form"),
        formData => formData.formType
      )
    FormEmailManager(formType).sendEmail()
  }
}
