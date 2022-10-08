package controllers

import dao.{RegisteredUserId, Secret, SlackTeam, SlackTeamDao}
import play.api.mvc.{AnyContent, BaseController, ControllerComponents, Request}
import play.api.{Configuration, Logging, mvc}
import services.{SlackManagerService, SlackSecretsManagerService}
import slack.{BoltException, SlackClient}
import util.Encodings.base64Decode

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

class SlackAuthController @Inject() (
    protected val config: Configuration,
    protected val slackTeamDao: SlackTeamDao,
    protected val slackSecretsManagerService: SlackSecretsManagerService,
    protected val slackManagerService: SlackManagerService,
    val controllerComponents: ControllerComponents
)(implicit val ec: ExecutionContext)
    extends BaseController
    with SlackClient
    with Logging {

  val AuthRegEx: Regex = """\((.*),(.*)\)""".r

  final case class InvalidAuthState(state: Option[String])
      extends Exception(s"Invalid state parameter: $state")

  protected def oauthV2Access(
      temporaryCode: String,
      userId: RegisteredUserId
  ): Future[SlackTeam] = {
    slackManagerService.oauthV2Access(
      slackClientId,
      slackClientSecret,
      temporaryCode,
      userId
    )
  }

  protected def verifyState(state: Option[String]): Future[RegisteredUserId] = {
    state match {
      case Some(AuthRegEx(uid, secretBase64)) =>
        slackSecretsManagerService.verifySecret(
          RegisteredUserId(uid),
          Secret(base64Decode(secretBase64))
        ) map {
          _.id
        }
      case _ =>
        Future.failed(InvalidAuthState(state))
    }
  }

  def authRedirect(
      temporaryCode: Option[String],
      error: Option[String],
      state: Option[String]
  ): mvc.Action[AnyContent] =
    Action.async { implicit request: Request[AnyContent] =>
      logger.debug(
        s"Received slash auth redirect with state $state and code $temporaryCode"
      )

      error match {

        case Some("access_denied") =>
          logger.info("User cancelled OAuth during 'Add to Slack'")
          Future { Ok(views.html.index(config.get[String]("slack.deployURL"))) }

        case Some(error) =>
          logger.error(s"Error during OAuth: $error")
          Future { ServiceUnavailable(error) }

        case None =>
          val f = for {
            userId <- verifyState(state)
            team <- oauthV2Access(temporaryCode.get, userId)
            _ <- slackSecretsManagerService.unbind(userId)
            n <- slackTeamDao.insertOrUpdate(team)
          } yield n

          f map { case 1 =>
            Ok(views.html.installed())
          } recover {
            case BoltException(message) =>
              ServiceUnavailable(s"Invalid user: $message")
            case ex: Exception =>
              logger.error(ex.getMessage)
              ex.printStackTrace()
              ServiceUnavailable(ex.getMessage)
          }
      }
    }
}
