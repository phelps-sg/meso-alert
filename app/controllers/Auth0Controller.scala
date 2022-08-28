package controllers

import play.api.Configuration
import play.api.libs.json.{Json, Writes}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import sttp.model.Uri
import util.ConfigLoaders.UriConfigLoader

import javax.inject.Inject

class Auth0Controller @Inject() (
    val controllerComponents: ControllerComponents,
    protected val config: Configuration
) extends BaseController {

  implicit val auth0ConfigurationWrites: Writes[Auth0Configuration] =
    (config: Auth0Configuration) =>
      Json.obj(
        "clientId" -> config.clientId,
        "domain" -> config.domain.toString(),
        "audience" -> config.audience.toString()
      )

  case class Auth0Configuration(clientId: String, domain: Uri, audience: Uri)

  val auth0Configuration: Auth0Configuration = Auth0Configuration(
    config.get[String]("auth0.clientId"),
    config.get[Uri]("auth0.domain"),
    config.get[Uri]("auth0.audience")
  )

  def configuration(): Action[AnyContent] = Action { _ =>
    Ok(Json.toJson(auth0Configuration))
  }
}
