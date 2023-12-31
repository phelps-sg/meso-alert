package controllers

import controllers.SlackAuthController.{
  AuthRegEx,
  InvalidAuthState,
  NoTemporaryCodeException
}
import dao._
import play.api.mvc._
import play.api.{Configuration, Logging, mvc}
import services.{SlackManagerService, SlackSecretsManagerService}
import slack.{BoltException, SlackClient}
import util.AsyncResultHelpers
import util.Encodings.base64Decode

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.matching.Regex

object SlackAuthController {

  val AuthRegEx: Regex = """\((.*),(.*)\)""".r

  final case class InvalidAuthState(state: Option[String])
      extends Exception(s"Invalid state parameter: $state")

  case object NoTemporaryCodeException
      extends Exception("Missing temporary code")

}

/** Controller for handling
  * [[https://api.slack.com/authentication/oauth-v2 Slack V2 Oauth 2.0]]
  */
class SlackAuthController @Inject() (
    protected val config: Configuration,
    protected val slackTeamDao: SlackTeamDao,
    protected val slackSecretsManagerService: SlackSecretsManagerService,
    protected val slackManagerService: SlackManagerService,
    val controllerComponents: ControllerComponents
)(implicit val ec: ExecutionContext)
    extends BaseController
    with AsyncResultHelpers
    with SlackClient
    with Logging {

  def authRedirect(
      temporaryCode: Option[String],
      error: Option[String],
      state: Option[String]
  ): mvc.Action[AnyContent] = {

    import DaoUtils._

    Action.async { // noinspection ScalaUnusedSymbol
      implicit request: Request[AnyContent] =>
        logger.debug(
          s"Received slash auth redirect with state $state and code $temporaryCode"
        )

        guardAgainst(error) {

          val f = for {
            userId <- verifyState(state)
            team <- oauthV2Access(temporaryCode, userId)
            _ <- slackSecretsManagerService.unbind(userId)
            _ <- slackTeamDao.insertOrUpdate(team).withException
          } yield Ok(views.html.installed())

          f recover {
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

  protected def oauthV2Access(
      temporaryCode: Option[String],
      userId: RegisteredUserId
  ): Future[SlackTeam] = {
    temporaryCode match {
      case Some(code) =>
        slackManagerService.oauthV2Access(
          slackClientId,
          slackClientSecret,
          code,
          userId
        )
      case None =>
        Future.failed(NoTemporaryCodeException)
    }
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

  private def guardAgainst(
      error: Option[String]
  )(block: => Future[Result]): Future[Result] = {
    error match {
      case Some("access_denied") =>
        logger.info("User cancelled OAuth during 'Add to Slack'")
        Ok(views.html.index(config.get[String]("slack.deployURL")))
      case Some(error) =>
        logger.error(s"Error during OAuth: $error")
        ServiceUnavailable(error)
      case None =>
        block
    }
  }
}
