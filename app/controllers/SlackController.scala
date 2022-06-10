package controllers

import org.slf4j.LoggerFactory
import play.api.mvc.{Action, BaseController, ControllerComponents}

import javax.inject.Inject

class SlackController @Inject() (val controllerComponents: ControllerComponents) extends BaseController {

  private val logger = LoggerFactory.getLogger(classOf[SlackController])

  def slashCommand: Action[Map[String, Seq[String]]] = Action(parse.formUrlEncoded) { request =>
    logger.debug("received slash command")
    request.body.foreach { x => logger.debug(s"${x._1} = ${x._2}") }
    logger.info(s"userId = ${request.body("user_id")}")
    logger.info(s"userName = ${request.body("user_name")}")
    logger.info(s"channelName = ${request.body("channel_name")}")
    logger.info(s"channelId = ${request.body("channel_id")}")
    Ok("Success")
  }

}
