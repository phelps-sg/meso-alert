package actors

import akka.actor.ActorRef
import play.api.Logger
import services.MemPoolWatcherService

trait TxUpdateActor {
  protected val logger: Logger
  val self: ActorRef
  val memPoolWatcher: MemPoolWatcherService

  def registerWithWatcher(): Unit = {
    logger.info("Registering new mem pool listener... ")
    memPoolWatcher.addListener(self)
    logger.info("registration complete.")
  }
}
