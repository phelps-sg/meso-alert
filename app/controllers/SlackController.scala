package controllers

import org.slf4j.LoggerFactory
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}

import javax.inject.Inject

case class SlashCommandDto(token: String, teamId: String, teamDomain: String, enterpriseId: String,
                           channelId: String, channelName: String, userId: String, userName: String,
                           command: String, text: String, responseUrl: String, triggerId: String,
                           apiAppId: String)

class SlackController @Inject() (val controllerComponents: ControllerComponents) extends BaseController {

  private val logger = LoggerFactory.getLogger(classOf[SlackController])

  def slashCommand: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    logger.info(request.body.toString)
    Ok("Success")
  }

}
