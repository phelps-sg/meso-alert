package controllers

import actors.HookNotStartedException

import javax.inject.Inject
import dao.SlackChannel
import play.api.Logging
import play.api.libs.json.JsValue
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.HooksManagerSlackChat

import scala.concurrent.{ExecutionContext}

class EventsController @Inject()(val controllerComponents: ControllerComponents,
                                 val hooksManager: HooksManagerSlackChat)
                                (implicit ec: ExecutionContext)
  extends BaseController with Logging {

  def eventsAPI(): Action[JsValue] = Action(parse.json) { implicit request =>
    val requestBody = request.body
    // deal with the one-time challenge sent from the Events api to verify ownership of url
    val isChallenge = (requestBody \ "challenge").asOpt[String]
    if (!isChallenge.isEmpty) {
      Ok(isChallenge.get)
    } else {
      val eventType = (requestBody \ "event" \ "type").as[String]
      eventType match {
        case "channel_deleted" =>
          val channel = (requestBody \ "event" \ "channel").as[String]
          val f = for {
            stopped <- hooksManager.stop(SlackChannel(channel))
          } yield stopped
          f.map { result => logger.info(s"Stopping hook ${result.hook} because channel was deleted.") }
            .recover {
              case HookNotStartedException(_) => logger.info("Channel with inactive hook was deleted.")
            }
        case _ => logger.info("Different event")
      }
      Ok("")
    }
  }
}
