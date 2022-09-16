package unittests

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import pdi.jwt.JwtClaim
import sttp.model.Uri
import util.JWT

import java.time.{Clock, Instant, ZoneId}
import scala.util.{Failure, Success, Try}

class JwtTests extends AnyWordSpecLike with should.Matchers {

  def clock20220907: Clock =
    Clock.fixed(Instant.parse("2022-09-07T00:13:30.00Z"), ZoneId.of("Etc/UTC"))

  def clock20220930: Clock =
    Clock.fixed(Instant.parse("2022-09-30T00:13:30.00Z"), ZoneId.of("Etc/UTC"))

  def domain: Uri =
    Uri.parse("blockinsights-staging.eu.auth0.com").getOrElse(null)

  def audience: Uri =
    Uri.parse("https://blockinsights.co.uk/unit-tests").getOrElse(null)

  val validateWithClock: Clock => String => Try[JwtClaim] =
    JWT.validateJwt(domain)(audience)

  val validate: String => Try[JwtClaim] = validateWithClock(clock20220907)

  val invalidJwtNoKid =
    "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJzdWIiOiIxMjM0NTY3ODkwIiwibmFtZSI6IkpvaG4gRG9lIiwiaWF0IjoxNTE2MjM5MDIyfQ.SflKxwRJSMeKKF2QT4fwpMeJf36POk6yJV_adQssw5c"

  val invalidJwtWrongFormat = "invalid-jwt"

  val invalidJwtInvalidSignature =
    "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6Ik44Q0JFSVhZT2EzOE0yTmhQdzhPcSJ9.eyJpc3MiOiJodHRwczovL2Jsb2NraW5zaWdodHMtc3RhZ2luZy5ldS5hdXRoMC5jb20vIiwic3ViIjoiQ25vNXo3OFBSWE5jUFJOb1ZrNHR0VDVaQ0g4WTdxdEZAY2xpZW50cyIsImF1ZCI6Imh0dHBzOi8vYmxvY2tpbnNpZ2h0cy5jby51ay91bml0LXRlc3RzIiwiaWF0IjoxNjYyOTY0MTE3LCJleHAiOjE2NjMwNTA1MTcsImF6cCI6IkNubzV6NzhQUlhOY1BSTm9WazR0dFQ1WkNIOFk3cXRGIiwiZ3R5IjoiY2xpZW50LWNyZWRlbnRpYWxzIn0.a-jcC5MnP5pk2YJzIGtPkkHvBmcw3g3Yo4RPhD5X_-hGka7MhGdeRNJxe9k90CRvYzAj4W6qGTF-EMhvPAgJ6SQxEN4WzrTA8UbSW0lMsulNOzebRmPHzGLnqM9sf7hwMePK21djs02LRAZw4lZwroHyuHKtklrP8JeQKKkouZjXFLKllR4Y-AdvKh2FMQFF2q-M9bq7GoYOQHwzGrJKDFu9-ALlHwgh2wnxJBiIzd_WZnEL3B-JNhHk_K16MXKtcJvxIJBkH3KF2J6CH88bGjG_OanR4gjdd2k2ZHCRo7g1LzQWQCXFjcge_fRs2PB9kXUmXbTT3WcB180oX-r15Q"

  val validJwt =
    "eyJhbGciOiJSUzI1NiIsInR5cCI6IkpXVCIsImtpZCI6Ik44Q0JFSVhZT2EzOE0yTmhQdzhPcSJ9.eyJpc3MiOiJodHRwczovL2Jsb2NraW5zaWdodHMtc3RhZ2luZy5ldS5hdXRoMC5jb20vIiwic3ViIjoiQ25vNXo3OFBSWE5jUFJOb1ZrNHR0VDVaQ0g4WTdxdEZAY2xpZW50cyIsImF1ZCI6Imh0dHBzOi8vYmxvY2tpbnNpZ2h0cy5jby51ay91bml0LXRlc3RzIiwiaWF0IjoxNjYyOTY0MTE3LCJleHAiOjE2NjMwNTA1MTcsImF6cCI6IkNubzV6NzhQUlhOY1BSTm9WazR0dFQ1WkNIOFk3cXRGIiwiZ3R5IjoiY2xpZW50LWNyZWRlbnRpYWxzIn0.a-jbC5MnP5pk2YJzIGtPkkHvBmcw3g3Yo4RPhD5X_-hGka7MhGdeRNJxe9k90CRvYzAj4W6qGTF-EMhvPAgJ6SQxEN4WzrTA8UbSW0lMsulNOzebRmPHzGLnqM9sf7hwMePK21djs02LRAZw4lZwroHyuHKtklrP8JeQKKkouZjXFLKllR4Y-AdvKh2FMQFF2q-M9bq7GoYOQHwzGrJKDFu9-ALlHwgh2wnxJBiIzd_WZnEL3B-JNhHk_K16MXKtcJvxIJBkH3KF2J6CH88bGjG_OanR4gjdd2k2ZHCRo7g1LzQWQCXFjcge_fRs2PB9kXUmXbTT3WcB180oX-r15Q"

  "validateJwt should fail for a token in the wrong format" in {
    validate(invalidJwtWrongFormat) should matchPattern { case Failure(_) => }
  }

  "validateJwt should fail for token with no keyid" in {
    validate(invalidJwtNoKid) should matchPattern { case Failure(_) => }
  }

  "validateJwt should succeed for valid token" in {
    validate(validJwt) match {
      case Success(_) => succeed
      case Failure(ex) =>
        ex.printStackTrace()
        fail(ex.getMessage)
    }
  }

  "validateJwt should fail for an invalid signature" in {
    validate(invalidJwtInvalidSignature) should matchPattern {
      case Failure(_) =>
    }
  }

  "validateJwt should fail for an expired token" in {
    validateWithClock(clock20220930)(validJwt) should matchPattern {
      case Failure(_) =>
    }
  }

}
