package actors

import akka.actor.Actor
import play.api.Logging

import scala.util.Failure

trait UnrecognizedMessageHandler { env: Actor with Logging =>

  def unrecognizedMessage(message: Any): Unit = {
    logger.error(s"Unrecognized message: $message")
  }

}

trait UnRecognizedMessageHandlerWithBounce extends UnrecognizedMessageHandler {
  env: Actor with Logging =>

  override def unrecognizedMessage(message: Any): Unit = {
    super.unrecognizedMessage(message)
    sender() ! Failure(new RuntimeException(s"Unrecognized message: $message"))
  }

}
