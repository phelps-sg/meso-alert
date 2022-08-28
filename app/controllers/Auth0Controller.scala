package controllers

import play.api.Configuration
import play.api.libs.json.{JsObject, Json, Writes}
import play.api.mvc.{Action, AnyContent, BaseController, ControllerComponents}
import sttp.model.Uri

import javax.inject.Inject

class Auth0Controller @Inject() (
    val controllerComponents: ControllerComponents,
    protected val config: Configuration
) extends BaseController {

 implicit val auth0ConfigurationWrites = new Writes[Auth0Configuration] {
    def writes(config: Auth0Configuration): JsObject = Json.obj(
      "clientId" -> config.clientId,
      "domain" -> config.domain.toString(),
      "audience" -> config.audience.toString()
    )
  }

  case class Auth0Configuration(clientId: String, domain: Uri, audience: Uri)

  def parseUri(configPath: String): Uri = {
    Uri.parse(config.get[String](configPath)) match {
      case Right(domain) =>
        domain
      case Left(error) =>
        throw new RuntimeException(error)
    }
  }

  val auth0Configuration = Auth0Configuration(
    config.get[String]("auth0.clientId"),
    parseUri("auth0.domain"),
    parseUri("auth0.domain")
  )

  def configuration(): Action[AnyContent] = Action { _ =>
    Ok(Json.toJson(auth0Configuration))
  }
}
