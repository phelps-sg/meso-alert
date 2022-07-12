package util

import play.api.Logging

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

trait FutureInitialisingComponent extends Logging {

  protected val initialisationTimeout: FiniteDuration = 1.minute
  implicit val ec: ExecutionContext

  protected def initialiseFuture(): Future[Unit]

  def initialise(): Unit = {
    logger.debug("Initialising.. ")
    val f = initialiseFuture()
    f.onComplete {
      case Success(_) => logger.info("Initialisation complete.")
      case Failure(ex) =>
        logger.error(s"Initialisation failed with ${ex.getMessage}")
        ex.printStackTrace()
    }
    Await.ready(f, initialisationTimeout)
  }
}
