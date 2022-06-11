package controllers

import akka.actor.ActorSystem
import dao.Webhook
import org.slf4j.LoggerFactory
import play.api.libs.json._
import play.api.mvc.{Action, BaseController, ControllerComponents, Result}
import services.HooksManagerWebService

import java.net.URI
import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class WebhooksController @Inject()(val controllerComponents: ControllerComponents,
                                   val slackWebHooksManager: HooksManagerWebService)
                                  (implicit system: ActorSystem, ex: ExecutionContext) extends BaseController {

  private val logger = LoggerFactory.getLogger(classOf[WebhooksController])

  case class UriDto(uri: String)
  case class HookDto(uri: String, threshold: Long)

  implicit val uriJson: OFormat[UriDto] = Json.format[UriDto]
  implicit val hookJson: OFormat[HookDto] = Json.format[HookDto]

  def checkEx[T](f: Future[T]): Future[Result] =
    f map(_ => Ok("Success")) recover { case ex => ServiceUnavailable(ex.getMessage) }

  def start: Action[UriDto] = Action.async(parse.json[UriDto]) { request =>
    checkEx(slackWebHooksManager.start(new URI(request.body.uri)))
  }

  def stop: Action[UriDto] = Action.async(parse.json[UriDto]) { request =>
    checkEx(slackWebHooksManager.stop(new URI(request.body.uri)))
  }

  def register: Action[HookDto] = Action.async(parse.json[HookDto]) { request =>
    checkEx(slackWebHooksManager.register(Webhook(new URI(request.body.uri), request.body.threshold)))
  }

}
