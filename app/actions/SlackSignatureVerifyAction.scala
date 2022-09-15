package actions

import akka.util.ByteString
import com.google.inject.Inject
import play.api.Logging
import play.api.mvc.Results.Unauthorized
import play.api.mvc._
import play.core.parsers.FormUrlEncodedParser
import services.SlackSignatureVerifierService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class SlackRequest[A](
    val validateSignature: String => Try[String],
    request: Request[A]
) extends WrappedRequest[A](request)

object SlackSignatureVerifyAction {

  implicit class SlackRequestByteStringValidator(
      slackRequest: SlackRequest[ByteString]
  ) {
    def validateSignatureAgainstBody(): Try[Map[String, Seq[String]]] = {
      val raw = slackRequest.body.utf8String
      slackRequest.validateSignature(raw) map { _ =>
        FormUrlEncodedParser.parse(new String(slackRequest.body.toArray))
      }
    }
  }

  val HEADERS_TIMESTAMP: String = "X-Slack-Request-Timestamp"
  val HEADERS_SIGNATURE: String = "X-Slack-Signature"
}

class SlackSignatureVerifyAction @Inject() (
    val parser: BodyParsers.Default,
    slackSignatureVerifierService: SlackSignatureVerifierService
)(implicit ec: ExecutionContext)
    extends ActionBuilder[SlackRequest, AnyContent]
    with ActionRefiner[Request, SlackRequest]
    with Logging {

  override protected def executionContext: ExecutionContext = ec

  override protected def refine[A](
      request: Request[A]
  ): Future[Either[Result, SlackRequest[A]]] = {

    val timestamp =
      request.headers.get(SlackSignatureVerifyAction.HEADERS_TIMESTAMP)

    val signature =
      request.headers.get(SlackSignatureVerifyAction.HEADERS_SIGNATURE)

    (timestamp, signature) match {
      case (Some(timestamp), Some(signature)) =>
        Future.successful {
          val validate = (body: String) =>
            slackSignatureVerifierService.validate(timestamp, body, signature)
          Right(new SlackRequest[A](validate, request))
        }
      case _ =>
        Future { Left(Unauthorized("Invalid signature headers")) }
    }

  }

}
