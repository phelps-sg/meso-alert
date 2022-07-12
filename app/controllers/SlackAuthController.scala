package controllers

import com.slack.api.methods.AsyncMethodsClient
import com.slack.api.methods.request.oauth.OAuthV2AccessRequest
import dao.{SlackTeam, SlackTeamDao}
import play.api.mvc.{AnyContent, BaseController, ControllerComponents, Request}
import play.api.{Configuration, Logging, mvc}
import slack.SlackClient

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters._

case class InvalidUserException(str: String) extends Exception(str)

class SlackAuthController @Inject()(protected val config: Configuration,
                                    val slackTeamDao: SlackTeamDao,
                                    val controllerComponents: ControllerComponents)
                                   (implicit val ec: ExecutionContext)
  extends BaseController with SlackClient with Logging {

  protected val slackMethods: AsyncMethodsClient = slack.methodsAsync()

  def authRedirect(temporaryCode: String): mvc.Action[AnyContent] =
    Action.async { implicit request: Request[AnyContent] =>

    logger.debug("Received slash auth redirect")

    val slackRequest = OAuthV2AccessRequest.builder
      .clientId(slackClientId)
      .clientSecret(slackClientSecret)
      .code(temporaryCode)
      .build()

    val f = for {

      response <- slackMethods.oauthV2Access(slackRequest).asScala

      n <- if (response.isOk) {
        val slackTeam =
          SlackTeam(teamId = response.getTeam.getId, userId = response.getAuthedUser.getId,
                    botId = response.getBotUserId, accessToken = response.getAccessToken,
                    teamName = response.getTeam.getName)
        logger.debug(s"user = $slackTeam")
        slackTeamDao.insertOrUpdate(slackTeam)
      } else {
        Future.failed(InvalidUserException(response.getError))
      }

    } yield n

    f map {
      case 1 =>
              Ok(views.html.installed())
    } recover {
      case ex: InvalidUserException =>
        ServiceUnavailable(s"invalid user ${ex.getMessage}")
    }

  }

}
