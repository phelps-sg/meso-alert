package slack

import actions.SignatureVerifyAction
import com.google.inject.Inject
import play.api.mvc.BodyParsers
import services.SignatureVerifierService

import scala.concurrent.ExecutionContext

class SlackSignatureVerifyAction @Inject() (
    parser: BodyParsers.Default,
    signatureVerifierService: SignatureVerifierService
)(implicit ec: ExecutionContext)
    extends SignatureVerifyAction(parser, signatureVerifierService) {
  override val headersTimestamp: String = "X-Slack-Request-Timestamp"
  override val headersSignature: String = "X-Slack-Signature"
}
