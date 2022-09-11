package unittests

import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import pdi.jwt.JwtClaim
import sttp.model.Uri
import util.JWT

import java.time.{Clock, Instant, ZoneId}
import scala.util.{Failure, Try}

class JwtTests extends AnyWordSpecLike with should.Matchers {

  def fixedClock: Clock =
    Clock.fixed(Instant.ofEpochSecond(0), ZoneId.of("Etc/UTC"))

  val validate: String => Try[JwtClaim] =
    JWT.validateJwt(Uri("test.domain"))(Uri("api.test.domain"))(fixedClock)

  val invalidJwt = "invalid-jwt"

  "validateJwt should fail for an invalid token" in {
    validate(invalidJwt) should matchPattern { case Failure(_) => }
  }

}
