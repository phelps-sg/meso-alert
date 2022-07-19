package slack

import actors.TxMessagingActorSlackChat.BoltException
import com.slack.api.methods.SlackApiTextResponse

import java.util.concurrent.CompletableFuture
import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.FutureConverters.CompletionStageOps

object FutureConverters {

  implicit class BoltFuture[X <: SlackApiTextResponse](completableFuture: CompletableFuture[X]) {
    def asScalaFuture(implicit ec: ExecutionContext): Future[X] =
        completableFuture.asScala map { result =>
      if (result.isOk) result else throw BoltException(result.getError)
    }
  }

}
