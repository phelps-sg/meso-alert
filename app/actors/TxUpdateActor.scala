package actors

import akka.actor.ActorRef
import org.slf4j.Logger
import services.MemPoolWatcherService

trait TxUpdateActor {
  val self: ActorRef
  val memPoolWatcher: MemPoolWatcherService
  val logger: Logger

  def registerWithWatcher(): Unit = {
    logger.info("Registering new mem pool listener... ")
    memPoolWatcher.addListener(self)
    logger.info("registration complete.")
  }
}
