package controllers

import org.slf4j.LoggerFactory
import play.api.libs.json.OFormat
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents, Request}
import play.api.libs.json._
import javax.inject.Inject

class SlackController @Inject() (val controllerComponents: ControllerComponents) extends BaseController {

  private val logger = LoggerFactory.getLogger(classOf[SlackController])

  case class SlashCommandDto(token: String, teamId: String, teamDomain: String, enterpriseId: String,
                             channelId: String, channelName: String, userId: String, userName: String,
                             command: String, text: String, responseUrl: String, triggerId: String,
                             apiAppId: String)

  implicit val slashCommandJson: OFormat[SlashCommandDto] = Json.format[SlashCommandDto]

  def slashCommand: Action[Map[String, Seq[String]]] = Action(parse.formUrlEncoded) { request =>
    logger.debug("received slash command")
    request.body.foreach{ x => logger.debug(s"${x._1} = ${x._2}") }
      logger.info(s"userId = ${request.body("user_id")}")
      logger.info(s"userName = ${request.body("user_name")}")
      logger.info(s"channelName = ${request.body("channel_name")}")
      logger.info(s"channelId = ${request.body("channel_id")}")
      Ok("Success")
    }

}
