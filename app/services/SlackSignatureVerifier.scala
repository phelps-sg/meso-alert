package services

import com.google.inject.{ImplementedBy, Inject, Singleton}
import play.api.Configuration

import scala.util.{Failure, Success, Try}

@ImplementedBy(classOf[SlackSignatureVerifier])
trait SlackSignatureVerifierService {
  def validate(
      timestamp: String,
      body: String,
      slackSignature: String
  ): Try[String]
}

@Singleton
class SlackSignatureVerifier @Inject() (protected val config: Configuration)
    extends SlackSignatureVerifierService {

  val signingSecret: String = config.get[String]("slack.signingSecret")

  def validate(
      timestamp: String,
      body: String,
      slackSignature: String
  ): Try[String] = {
    import javax.crypto.Mac
    import javax.crypto.spec.SecretKeySpec
    import javax.xml.bind.DatatypeConverter

    val secret = new SecretKeySpec(signingSecret.getBytes, "HmacSHA256")
    val payload = s"v0:$timestamp:$body"

    val mac = Mac.getInstance("HmacSHA256")
    mac.init(secret)

    val signatureBytes = mac.doFinal(payload.getBytes)
    val expectedSignature =
      s"v0=${DatatypeConverter.printHexBinary(signatureBytes).toLowerCase}"
    if (slackSignature == expectedSignature) {
      Success(body)
    } else {
      Failure(new Exception("Invalid signature"))
    }
  }
}
