package controllers

import org.slf4j.LoggerFactory
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}

import javax.inject.Inject

class SlackController @Inject() (val controllerComponents: ControllerComponents) extends BaseController {

  private val logger = LoggerFactory.getLogger(classOf[SlackController])

  def slashCommand: Action[AnyContent] = Action { implicit request: Request[AnyContent] =>
    logger.info(request.body.toString)
    Ok("Success")
  }

}
