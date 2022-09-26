package slack

import com.slack.api.methods.SlackApiTextResponse

import java.util.concurrent.CompletableFuture
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.CompletionStageOps

case class BoltException(msg: String) extends Exception(msg)

object FutureConverters {

  def checkIsOK[T <: SlackApiTextResponse](result: T): T = {
    if (result.isOk) result else throw BoltException(result.getError)
  }

  def BoltFuture[T <: SlackApiTextResponse](
      block: => T
  )(implicit ec: ExecutionContext): Future[T] = {
    Future { checkIsOK(block) }
  }

}
