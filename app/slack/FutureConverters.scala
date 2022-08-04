package slack

import com.slack.api.methods.SlackApiTextResponse

import java.util.concurrent.CompletableFuture
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.CompletionStageOps

case class BoltException(msg: String) extends Exception(msg)

object FutureConverters {

  implicit class BoltFuture[X <: SlackApiTextResponse](
      completableFuture: CompletableFuture[X]
  ) {
    def asScalaFuture(implicit ec: ExecutionContext): Future[X] =
      completableFuture.asScala map { result =>
        if (result.isOk) result else throw BoltException(result.getError)
      }
  }

}
