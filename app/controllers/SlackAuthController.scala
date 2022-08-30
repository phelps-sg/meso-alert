package controllers

import com.slack.api.methods.AsyncMethodsClient
import com.slack.api.methods.request.oauth.OAuthV2AccessRequest
import dao.{SlackTeam, SlackTeamDao}
import play.api.mvc.{AnyContent, BaseController, ControllerComponents, Request}
import play.api.{Configuration, Logging, mvc}
import slack.FutureConverters.BoltFuture
import slack.{BoltException, SlackClient}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SlackAuthController @Inject() (
    protected val config: Configuration,
    val slackTeamDao: SlackTeamDao,
    val controllerComponents: ControllerComponents
)(implicit val ec: ExecutionContext)
    extends BaseController
    with SlackClient
    with Logging {

  protected val slackMethods: AsyncMethodsClient = slack.methodsAsync()

  def authRedirect(
      temporaryCode: Option[String],
      error: Option[String],
      state: String
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
          val slackRequest = OAuthV2AccessRequest.builder
            .clientId(slackClientId)
            .clientSecret(slackClientSecret)
            .code(temporaryCode.get)
            .build()

          val f = for {

            response <- slackMethods.oauthV2Access(slackRequest).asScalaFuture

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
