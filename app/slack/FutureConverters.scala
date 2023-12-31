package slack

import com.slack.api.methods.SlackApiTextResponse

import scala.concurrent.{ExecutionContext, Future}

final case class BoltException(msg: String) extends Exception(msg)
object BoltException {
  private val ERROR_CHANNEL_NOT_FOUND = "channel_not_found"
  val ChannelNotFoundException: BoltException = BoltException(
    ERROR_CHANNEL_NOT_FOUND
  )
}

object FutureConverters {

  def BoltFuture[T <: SlackApiTextResponse](
      block: => T
  )(implicit ec: ExecutionContext): Future[T] = {
    Future { checkIsOK(block) }
  }

  private def checkIsOK[T <: SlackApiTextResponse](result: T): T = {
    if (result.isOk) result else throw BoltException(result.getError)
  }

}
