package actions

import actions.SlackSignatureVerifyAction.SlackRequestByteStringValidator
import akka.util.ByteString
import com.google.inject.Inject
import play.api.Logging
import play.api.mvc.Results.Unauthorized
import play.api.mvc._
import play.core.parsers.FormUrlEncodedParser
import services.SignatureVerifierService

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class SlackRequest[A](
    val validateSignature: String => Try[String],
    request: Request[A]
) extends WrappedRequest[A](request)

trait SlackSignatureHelpers { env: BaseControllerHelpers =>

  def validateSignatureAndProcess[T](
      slackSignatureVerifyAction: SlackSignatureVerifyAction
  )(
      parser: Array[Byte] => T
  )(
      processor: T => Future[Result]
  )(implicit ec: ExecutionContext): Action[ByteString] =
    slackSignatureVerifyAction.async(parse.byteString) { request =>
      request
        .validateSignatureAgainstBody(parser)
        .map(processor) match {
        case Success(result) => result
        case Failure(ex) =>
          ex.printStackTrace()
          Future {
            Unauthorized(ex.getMessage)
          }
      }
    }
}

object SlackSignatureVerifyAction {

  def formUrlEncodedParser(rawBody: Array[Byte]): Map[String, Seq[String]] =
    FormUrlEncodedParser.parse(new String(rawBody))

  implicit class SlackRequestByteStringValidator(
      slackRequest: SlackRequest[ByteString]
  ) {
    def validateSignatureAgainstBody[T](parser: Array[Byte] => T): Try[T] = {
      val raw = slackRequest.body.utf8String
      slackRequest.validateSignature(raw) map { _ =>
        parser(slackRequest.body.toArray)
      }
    }
  }

  val HEADERS_TIMESTAMP: String = "X-Slack-Request-Timestamp"
  val HEADERS_SIGNATURE: String = "X-Slack-Signature"
}

class SlackSignatureVerifyAction @Inject() (
    val parser: BodyParsers.Default,
    signatureVerifierService: SignatureVerifierService
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
            signatureVerifierService.validate(timestamp, body, signature)
          Right(new SlackRequest[A](validate, request))
        }
      case _ =>
        Future { Left(Unauthorized("Invalid signature headers")) }
    }

  }

}
