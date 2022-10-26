package controllers

import actions.SignatureHelpers
import actors.HookNotStartedException
import akka.util.ByteString
import dao.SlackChannelId
import play.api.Logging
import play.api.libs.json.{JsValue, Json}
import play.api.mvc.{Action, BaseController, ControllerComponents, Result}
import services.HooksManagerSlackChat
import slack.SlackSignatureVerifyAction

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SlackEventsController @Inject() (
    val controllerComponents: ControllerComponents,
    val hooksManager: HooksManagerSlackChat,
    protected val slackSignatureVerifyAction: SlackSignatureVerifyAction
)(implicit ec: ExecutionContext)
    extends BaseController
    with SignatureHelpers
    with Logging {

  protected val whenSignatureValid
      : (JsValue => Future[Result]) => Action[ByteString] =
    whenSignatureValid(slackSignatureVerifyAction)(Json.parse)

  def eventsAPI(): Action[ByteString] =
    whenSignatureValid { requestBody =>
      {
        val isChallenge = (requestBody \ "challenge").asOpt[String]
        isChallenge match {
          case Some(challengeValue) =>
            Future {
              Ok(challengeValue)
            }
          case None =>
            val eventType = (requestBody \ "event" \ "type").as[String]
            eventType match {
              case "channel_deleted" =>
                val channel = (requestBody \ "event" \ "channel").as[String]
                stopHook(SlackChannelId(channel))
              case ev =>
                logger.warn(s"Received unhandled event $ev")
                Future {
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
        s"Stopping hook ${result.hook} because channel was deleted."
      )
      Ok
    }.recover { case HookNotStartedException(key) =>
      logger.info(s"Channel with inactive hook $key was deleted.")
      Gone
    }
  }

}
