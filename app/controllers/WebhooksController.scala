package controllers

import actors.WebhooksActor.{Webhook, WebhookNotRegisteredException}
import akka.actor.ActorSystem
import play.api.libs.json._
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.{MemPoolWatcherService, SlackWebhooksManagerService, UserManagerService}

import java.net.URI
import javax.inject.Inject
import scala.concurrent.ExecutionContext

class WebhooksController @Inject()(val controllerComponents: ControllerComponents,
                                   val memPoolWatcher: MemPoolWatcherService,
                                   val userManager: UserManagerService,
                                   val slackWebHooksManager: SlackWebhooksManagerService)
                                  (implicit system: ActorSystem, ex: ExecutionContext) extends BaseController {

  case class UriDto(uri: String)
  case class HookDto(uri: String, threshold: Long)

  implicit val uriJson: OFormat[UriDto] = Json.format[UriDto]
  implicit val hookJson: OFormat[HookDto] = Json.format[HookDto]

  def start: Action[UriDto] = Action.async(parse.json[UriDto]) { request =>
    slackWebHooksManager.start(new URI(request.body.uri)).map(_ => Ok("Success"))
      .recover {
        case WebhookNotRegisteredException(uri) => NotFound(s"No web hook for $uri")
      }
  }

  def stop: Action[UriDto] = Action.async(parse.json[UriDto]) { request =>
    slackWebHooksManager.stop(new URI(request.body.uri)).map(_ => Ok("Success"))
  }

  def register: Action[HookDto] = Action.async(parse.json[HookDto]) { request =>
    slackWebHooksManager.register(Webhook(new URI(request.body.uri), request.body.threshold)).map(_ => Ok("Success"))
  }

}
