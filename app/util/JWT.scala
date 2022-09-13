package util

import com.auth0.jwk.{Jwk, UrlJwkProvider}
import pdi.jwt.{JwtAlgorithm, JwtBase64, JwtClaim, JwtJson}
import sttp.model.Uri

import java.time.Clock
import scala.util.{Failure, Success, Try}

object JWT {

  // A regex that defines the JWT pattern and allows us to
  // extract the header, claims and signature
  private val jwtRegex = """(.+?)\.(.+?)\.(.+?)""".r

  def validateJwt(
      domain: => Uri
  )(audience: => Uri)(clock: Clock)(token: String): Try[JwtClaim] = {
    for {
      jwk <- getJwk(domain, token) // Get the secret key for this token
      claims <- JwtJson(clock).decode(
        token,
        jwk.getPublicKey,
        Seq(JwtAlgorithm.RS256)
      ) // Decode the token using the secret key
      _ <- validateClaims(domain)(audience)(clock)(
        claims
      ) // validate the data stored inside the token
    } yield claims
  }

  private def splitToken(jwt: String): Try[(String, String, String)] =
    jwt match {
      case jwtRegex(header, body, sig) => Success((header, body, sig))
      case _ =>
        Failure(new Exception("Token does not match the correct pattern"))
    }

  def decodeElements(
      data: Try[(String, String, String)]
  ): Try[(String, String, String)] =
    data map { case (header, body, sig) =>
      (JwtBase64.decodeString(header), JwtBase64.decodeString(body), sig)
    }

  def getJwk(domain: => Uri, token: String): Try[Jwk] =
    (splitToken _ andThen decodeElements)(token) flatMap {
      case (header, _, _) =>
        val jwtHeader = JwtJson.parseHeader(header) // extract the header
        val jwkProvider = new UrlJwkProvider(s"https://$domain")

        // Use jwkProvider to load the JWKS data and return the JWK
        jwtHeader.keyId.map { k =>
          Try(jwkProvider.get(k))
        } getOrElse Failure(new Exception("Unable to retrieve kid"))
    }

  def validateClaims(
      domain: => Uri
  )(audience: => Uri)(clock: Clock)(claims: JwtClaim): Try[JwtClaim] =
    if (claims.isValid(issuer(domain), audience.toString())(clock)) {
      Success(claims)
    } else {
      Failure(new Exception("JWT did not pass validation"))
    }

  def issuer(domain: => Uri): String = s"https://$domain/"
}
