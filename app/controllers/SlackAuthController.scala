package controllers

import com.slack.api.methods.request.oauth.OAuthV2AccessRequest
import dao.{SlackUser, SlackUserDao}
import play.api.mvc.{AnyContent, BaseController, ControllerComponents, Request}
import play.api.{Configuration, Logging}
import slack.SlackClient

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters._
import play.api.mvc

case class InvalidUserException(str: String) extends Exception(str)

class SlackAuthController @Inject()(protected val config: Configuration,
                                    val slackUserDao: SlackUserDao,
                                    val controllerComponents: ControllerComponents)
                                   (implicit val ec: ExecutionContext)
  extends BaseController with SlackClient with Logging with InitialisingController {

  override def init(): Future[Unit] = {
    slackUserDao.init()
  }

  def authRedirect(temporaryCode: String): mvc.Action[AnyContent] =
    Action.async { implicit request: Request[AnyContent] =>

    logger.debug("Received slash auth redirect")

    val request = OAuthV2AccessRequest.builder
      .clientId(slackClientId)
      .clientSecret(slackClientSecret)
      .code(temporaryCode)
      .build()

    val f = for {

      response <- slackMethods.oauthV2Access(request).asScala

      n <- if (response.isOk) {
        val slackUser =
          SlackUser(response.getAuthedUser.getId, response.getBotUserId, response.getAccessToken,
            response.getTeam.getId, response.getTeam.getName)
        logger.debug(s"user = $slackUser")
        slackUserDao.insertOrUpdate(slackUser)
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
