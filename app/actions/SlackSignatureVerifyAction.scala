package actions

import akka.util.ByteString
import com.google.inject.Inject
import play.api.Logging
import play.api.mvc._
import play.core.parsers.FormUrlEncodedParser
import services.SlackSignatureVerifierService

import java.nio.charset.Charset
import scala.concurrent.{ExecutionContext, Future}
import scala.util.Try

class SlackRequest[A](
    val validateSignature: String => Try[String],
    request: Request[A]
) extends WrappedRequest[A](request)

object SlackSignatureVerifyAction {

  implicit class SlackRequestAnyContent(
      slackRequest: SlackRequest[ByteString]
  ) {
    def validateSignatureAgainstBody(): Try[Map[String, Seq[String]]] = {
      val raw = slackRequest.body.decodeString(Charset.forName("US-ASCII"))
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
    with ActionTransformer[Request, SlackRequest]
    with Logging {

  override protected def executionContext: ExecutionContext = ec

//  override def invokeBlock[A](
//      request: Request[A],
//      block: Request[A] => Future[Result]
//  ): Future[Result] = {
//
//    val timestamp =
//      request.headers.get(SlackSignatureVerifyAction.HEADERS_TIMESTAMP)
//
//    val signature =
//      request.headers.get(SlackSignatureVerifyAction.HEADERS_SIGNATURE)
//
//    slackSignatureVerifierService.validate(
//      timestamp,
//      Some(""),
//      signature
//    ) match {
//      case Success(()) =>
//        logger.debug("Successfully validated Slack signature")
//        block(request)
//      case Failure(ex) =>
//        logger.warn(ex.getMessage)
//        Future.successful(Results.Unauthorized)
//    }
//  }

  override protected def transform[A](
      request: Request[A]
  ): Future[SlackRequest[A]] = {
    val timestamp =
      request.headers.get(SlackSignatureVerifyAction.HEADERS_TIMESTAMP)

    val signature =
      request.headers.get(SlackSignatureVerifyAction.HEADERS_SIGNATURE)

    (timestamp, signature) match {
      case (Some(timestamp), Some(signature)) =>
        Future.successful {
          val validate = (body: String) =>
            slackSignatureVerifierService.validate(timestamp, body, signature)
          new SlackRequest[A](validate, request)
        }
      case _ =>
        Future.failed(new Exception("invalid headers"))
    }

  }
}
