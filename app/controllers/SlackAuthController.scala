package controllers

import com.slack.api.methods.AsyncMethodsClient
import com.slack.api.methods.request.oauth.OAuthV2AccessRequest
import dao.{Secret, SlackTeam, SlackTeamDao, UserId}
import play.api.mvc.{AnyContent, BaseController, ControllerComponents, Request}
import play.api.{Configuration, Logging, mvc}
import services.SlackSecretsManagerService
import slack.FutureConverters.BoltFuture
import slack.{BoltException, SlackClient}
import util.Encodings.base64Decode

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SlackAuthController @Inject() (
    protected val config: Configuration,
    val slackTeamDao: SlackTeamDao,
    val slackSecretsManagerService: SlackSecretsManagerService,
    val controllerComponents: ControllerComponents
)(implicit val ec: ExecutionContext)
    extends BaseController
    with SlackClient
    with Logging {

  case class InvalidAuthState(state: Option[String])
      extends Exception(s"Invalid state parameter: $state")

  protected val slackMethods: AsyncMethodsClient = slack.methodsAsync()

  def authRedirect(
      temporaryCode: Option[String],
      error: Option[String],
      state: Option[String]
  ): mvc.Action[AnyContent] =
    Action.async { implicit request: Request[AnyContent] =>
      logger.debug(s"Received slash auth redirect with state $state")

      error match {

        case Some("access_denied") =>
          logger.info("User cancelled OAuth during 'Add to Slack'")
          Future { Ok(views.html.index(config.get[String]("slack.deployURL"))) }

        case Some(error) =>
          logger.error(s"Error during OAuth: $error")
          Future { ServiceUnavailable(error) }

        case None =>
          val AuthRegEx = """\((.*),(.*)\)""".r

          val slackRequest = OAuthV2AccessRequest.builder
            .clientId(slackClientId)
            .clientSecret(slackClientSecret)
            .code(temporaryCode.get)
            .build()

          val f = for {

            userId <- state match {
              case Some(AuthRegEx(uid, secretBase64)) =>
                slackSecretsManagerService.verifySecret(
                  UserId(uid),
                  Secret(base64Decode(secretBase64))
                ) map {
                  _.id
                }
              case _ =>
                Future.failed(InvalidAuthState(state))
            }

            response <- slackMethods.oauthV2Access(slackRequest).asScalaFuture

            _ <- slackSecretsManagerService.unbind(userId)

            n <- {
              val slackTeam =
                SlackTeam(
                  teamId = response.getTeam.getId,
                  userId = response.getAuthedUser.getId,
                  botId = response.getBotUserId,
                  accessToken = response.getAccessToken,
                  teamName = response.getTeam.getName
                )
              logger.debug(s"user = $slackTeam")
              slackTeamDao.insertOrUpdate(slackTeam)
            }

          } yield n

          f map { case 1 =>
            Ok(views.html.installed())
          } recover { case BoltException(message) =>
            ServiceUnavailable(s"Invalid user: $message")
          }
      }
    }
}
