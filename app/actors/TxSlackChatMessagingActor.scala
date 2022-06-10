package actors

import akka.actor.Actor
import org.slf4j.LoggerFactory

class TxSlackChatMessagingActor extends Actor {

  private val logger = LoggerFactory.getLogger(classOf[TxSlackChatMessagingActor])

  override def receive: Receive = {
    case tx: TxUpdate =>
      logger.debug("Received $tx")

  }
}
