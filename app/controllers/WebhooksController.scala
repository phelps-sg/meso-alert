package controllers

import actors.WebhooksManagerActor.WebhookNotRegisteredException
import akka.actor.ActorSystem
import dao.Webhook
import play.api.libs.json._
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.SlackWebhooksManagerService

import java.net.URI
import javax.inject.Inject
import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

class WebhooksController @Inject()(val controllerComponents: ControllerComponents,
                                   val slackWebHooksManager: SlackWebhooksManagerService)
                                  (implicit system: ActorSystem, ex: ExecutionContext) extends BaseController {

  case class UriDto(uri: String)
  case class HookDto(uri: String, threshold: Long)

  implicit val uriJson: OFormat[UriDto] = Json.format[UriDto]
  implicit val hookJson: OFormat[HookDto] = Json.format[HookDto]

  def start: Action[UriDto] = Action.async(parse.json[UriDto]) { request =>
    slackWebHooksManager.start(new URI(request.body.uri)).map {
      case Success(_) => Ok("Success")
      case Failure(WebhookNotRegisteredException(uri)) => NotFound(s"No web hook for $uri")
      case Failure(ex) => ServiceUnavailable(ex.getMessage)
    }
  }

  def stop: Action[UriDto] = Action.async(parse.json[UriDto]) { request =>
    slackWebHooksManager.stop(new URI(request.body.uri)).map {
      case Success(_) => Ok("Success")
      case Failure(ex) => ServiceUnavailable(ex.getMessage)
    }
  }

  def register: Action[HookDto] = Action.async(parse.json[HookDto]) { request =>
    slackWebHooksManager.register(Webhook(new URI(request.body.uri), request.body.threshold)).map {
      case Success(_) => Ok("Success")
      case Failure(ex) => ServiceUnavailable(ex.getMessage)
    }
  }

}
