package actors

import akka.actor.ActorRef
import play.api.Logging
import services.MemPoolWatcherService

trait TxUpdateActor extends Logging {
  val self: ActorRef
  val memPoolWatcher: MemPoolWatcherService

  def registerWithWatcher(): Unit = {
    logger.info("Registering new mem pool listener... ")
    memPoolWatcher.addListener(self)
    logger.info("registration complete.")
  }
}
