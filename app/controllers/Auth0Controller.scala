package controllers

import actions.Auth0ValidateJWTAction
import controllers.Auth0Controller.{Auth0Configuration, Result}
import dao.{RegisteredUserId, Secret}
import play.api.Configuration
import play.api.libs.json.{Json, Writes}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import services.SlackSecretsManagerService
import sttp.model.Uri
import util.AsyncResultHelpers
import util.ConfigLoaders.UriConfigLoader
import util.Encodings.base64Encode

import java.util.Base64
import javax.inject.Inject
import scala.concurrent.ExecutionContext

object Auth0Controller {

  implicit val auth0ConfigurationWrites: Writes[Auth0Configuration] =
    (config: Auth0Configuration) =>
      Json.obj(
        "clientId" -> config.clientId,
        "domain" -> config.domain.toString(),
        "audience" -> config.audience.toString()
      )

  implicit val resultWrites: Writes[Result] =
    (result: Result) =>
      Json.obj(
        "userId" -> result.userId.value,
        "secret" -> base64Encode(result.secret.data),
        "slackUrl" -> result.slackUrl
      )

  final case class Auth0Configuration(
      clientId: String,
      domain: Uri,
      audience: Uri
  )

  final case class Result(
      userId: RegisteredUserId,
      secret: Secret,
      slackUrl: String
  )

}

class Auth0Controller @Inject() (
    val authAction: Auth0ValidateJWTAction,
    val slackSecretsManagerService: SlackSecretsManagerService,
    val controllerComponents: ControllerComponents,
    protected val config: Configuration
)(implicit ec: ExecutionContext)
    extends BaseController
    with AsyncResultHelpers {

  protected val encoder: Base64.Encoder = java.util.Base64.getEncoder
  protected val slackUrl: String = config.get[String]("slack.deployURL")

  private val auth0Configuration: Auth0Configuration = Auth0Configuration(
    config.get[String]("auth0.clientId"),
    config.get[Uri]("auth0.domain"),
    config.get[Uri]("auth0.audience")
  )

  def configuration(): Action[AnyContent] = Action { _ =>
    Ok(Json.toJson(auth0Configuration))
  }

  def secret(uid: Option[String]): Action[AnyContent] = authAction.async { _ =>
    uid match {

      case Some(identifier) =>
        val userId = RegisteredUserId(identifier)
        slackSecretsManagerService.generateSecret(userId) map { secret =>
          Ok(Json.toJson(Result(userId, secret, slackUrl)))
        }

      case None =>
        ServiceUnavailable("user is not logged in")
    }
  }
}
