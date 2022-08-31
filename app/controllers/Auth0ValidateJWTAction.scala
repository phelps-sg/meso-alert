package controllers

import javax.inject.Inject
import pdi.jwt._
import play.api.{Configuration, Logging}
import play.api.http.HeaderNames
import play.api.mvc._
import sttp.model.Uri
import util.JWT
import util.ConfigLoaders.UriConfigLoader

import java.time.{Clock, ZoneId}
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

case class UserRequest[A](jwt: JwtClaim, token: String, request: Request[A])
    extends WrappedRequest[A](request)

class Auth0ValidateJWTAction @Inject()(
    bodyParser: BodyParsers.Default,
    config: Configuration
)(implicit ec: ExecutionContext)
    extends ActionBuilder[UserRequest, AnyContent] with Logging {

  override def parser: BodyParser[AnyContent] = bodyParser
  override protected def executionContext: ExecutionContext = ec

  // A regex for parsing the Authorization header value
  private val headerTokenRegex = """Bearer (.+?)""".r

  protected val validateJwt: String => Try[JwtClaim] =
    JWT.validateJwt(config.get[Uri]("auth0.domain"))(
      config.get[Uri]("auth0.audience")
    )(Clock.system(ZoneId.of("Etc/UTC")))(_)

  // Called when a request is invoked. We should validate the bearer token here
  // and allow the request to proceed if it is valid.
  override def invokeBlock[A](
      request: Request[A],
      block: UserRequest[A] => Future[Result]
  ): Future[Result] =
    extractBearerToken(request) map { token =>
      validateJwt(token) match {
        case Success(claim) =>
          block(
            UserRequest(claim, token, request)
          ) // token was valid - proceed!
        case Failure(t) =>
          logger.debug(s"JWT auth failed with ${t.getMessage}")
          Future.successful(
            Results.Unauthorized(t.getMessage)
          ) // token was invalid - return 401
      }
    } getOrElse {
      logger.debug("JWT auth failed because no token was sent")
      Future.successful(
        Results.Unauthorized
      )
    } // no token was sent - return 401

  // Helper for extracting the token value
  private def extractBearerToken[A](request: Request[A]): Option[String] =
    request.headers.get(HeaderNames.AUTHORIZATION) collect {
      case headerTokenRegex(token) => token
    }
}
