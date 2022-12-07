package controllers

import actors.HookNotStartedException
import akka.util.ByteString
import com.mesonomics.playhmacsignatures.{
  HMACSignatureHelpers,
  SlackSignatureVerifyAction
}
import dao.SlackChannelId
import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.HooksManagerSlackChat

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SlackEventsController @Inject() (
    val controllerComponents: ControllerComponents,
    val hooksManager: HooksManagerSlackChat,
    protected implicit val slackSignatureVerifyAction: SlackSignatureVerifyAction
)(implicit ec: ExecutionContext)
    extends BaseController
    with HMACSignatureHelpers
    with Logging {

  private val onSignatureValid = validateSignatureAsync(Json.parse)(_)

  def eventsAPI(): Action[ByteString] =
    onSignatureValid { body: JsValue =>
      {
        val isChallenge = (body \ "challenge").asOpt[String]
        isChallenge match {
          case Some(challengeValue) =>
            Future.successful {
              Ok(challengeValue)
            }
          case None =>
            val eventType = (body \ "event" \ "type").as[String]
            eventType match {
              case "channel_deleted" =>
                val channel = (body \ "event" \ "channel").as[String]
                stopHook(SlackChannelId(channel))
              case ev =>
                logger.warn(s"Received unhandled event $ev")
                Future.successful {
                  NotAcceptable
                }
            }
        }
      }
    }

  def stopHook(channelId: SlackChannelId): Future[Status] = {
    val f = for {
      stopped <- hooksManager.stop(channelId)
    } yield stopped
    f.map { result =>
      logger.info(
        s"Stopping hook because channel ${result.hook.channel} was deleted."
      )
      Ok
    }.recover { case HookNotStartedException(key) =>
      logger.info(s"Channel with inactive hook $key was deleted.")
      Gone
    }
  }

}
