package actors

import akka.actor.Actor
import play.api.Logging

import scala.util.Failure

object MessageHandlers {

  def error(message: Any): String = s"Unrecognized message: $message"

  trait UnrecognizedMessageHandler {
    env: Actor with Logging =>

    def unrecognizedMessage(message: Any): Unit = {
      logger.error(error(message))
    }
  }

  trait UnrecognizedMessageHandlerFatal extends UnrecognizedMessageHandler {
    env: Actor with Logging =>

    override def unrecognizedMessage(message: Any): Unit = {
      super.unrecognizedMessage(message)
      throw new RuntimeException(error(message))
    }
  }

  trait UnRecognizedMessageHandlerWithBounce
      extends UnrecognizedMessageHandler {
    env: Actor with Logging =>

    override def unrecognizedMessage(message: Any): Unit = {
      super.unrecognizedMessage(message)
      sender() ! Failure(new RuntimeException(error(message)))
    }
  }

}
