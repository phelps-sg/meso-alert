package controllers

import play.api.Logger

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait InitialisingController {

  def init(): Future[Unit]
  protected val logger: Logger
  implicit val ec: ExecutionContext

  init().onComplete {
    case Success(_) => logger.info("Initialisation complete.")
    case Failure(ex) =>
      logger.error(s"Initialisation failed with ${ex.getMessage}")
      ex.printStackTrace()
  }
}
