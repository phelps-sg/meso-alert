package actions

import actions.SignatureVerifyAction.SignedRequestByteStringValidator
import akka.util.ByteString
import play.api.Logging
import play.api.mvc.Results.Unauthorized
import play.api.mvc._
import play.core.parsers.FormUrlEncodedParser
import services.SignatureVerifierService
import slack.SlackSignatureVerifyAction

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class SignedRequest[A](
    val validateSignature: String => Try[String],
    request: Request[A]
) extends WrappedRequest[A](request)

trait SignatureHelpers { env: BaseControllerHelpers =>

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

object SignatureVerifyAction {

  def formUrlEncodedParser(rawBody: Array[Byte]): Map[String, Seq[String]] =
    FormUrlEncodedParser.parse(new String(rawBody))

  implicit class SignedRequestByteStringValidator(
      slackRequest: SignedRequest[ByteString]
  ) {
    def validateSignatureAgainstBody[T](parser: Array[Byte] => T): Try[T] = {
      val raw = slackRequest.body.utf8String
      slackRequest.validateSignature(raw) map { _ =>
        parser(slackRequest.body.toArray)
      }
    }
  }
}

abstract class SignatureVerifyAction(
    val parser: BodyParsers.Default,
    signatureVerifierService: SignatureVerifierService
)(implicit ec: ExecutionContext)
    extends ActionBuilder[SignedRequest, AnyContent]
    with ActionRefiner[Request, SignedRequest]
    with Logging {

  val headersTimestamp: String
  val headersSignature: String

  override protected def executionContext: ExecutionContext = ec

  override protected def refine[A](
      request: Request[A]
  ): Future[Either[Result, SignedRequest[A]]] = {

    val timestamp = request.headers.get(headersTimestamp)
    val signature = request.headers.get(headersSignature)

    (timestamp, signature) match {
      case (Some(timestamp), Some(signature)) =>
        Future.successful {
          val validate = (body: String) =>
            signatureVerifierService.validate(timestamp, body, signature)
          Right(new SignedRequest[A](validate, request))
        }
      case _ =>
        Future { Left(Unauthorized("Invalid signature headers")) }
    }
  }
}
