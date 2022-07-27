package controllers

import actors.HookNotStartedException
import dao.SlackChannel
import play.api.Logging
import play.api.libs.json.JsValue
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.HooksManagerSlackChat

import javax.inject.Inject
import scala.concurrent.ExecutionContext

class SlackEventsController @Inject()(val controllerComponents: ControllerComponents,
                                 val hooksManager: HooksManagerSlackChat)
                                (implicit ec: ExecutionContext)
  extends BaseController with Logging {

  def eventsAPI(): Action[JsValue] = Action(parse.json) { implicit request =>
    val requestBody = request.body
    // deal with the one-time challenge sent from the Events api to verify ownership of url
    val isChallenge = (requestBody \ "challenge").asOpt[String]
    isChallenge match {
      case Some(challengeValue) => Ok(challengeValue)
      case None =>
        val eventType = (requestBody \ "event" \ "type").as[String]
        eventType match {
          case "channel_deleted" =>
            val channel = (requestBody \ "event" \ "channel").as[String]
            val f = for {
              stopped <- hooksManager.stop(SlackChannel(channel))
            } yield stopped
            f.map { result => logger.info(s"Stopping hook ${result.hook} because channel was deleted.") }
              .recover {
                case HookNotStartedException(key) => logger.info(s"Channel with inactive hook ${key} was deleted.")
              }
          case ev => logger.debug(s"Received unhandled event $ev")
        }
        Ok("")
    }
  }
}
