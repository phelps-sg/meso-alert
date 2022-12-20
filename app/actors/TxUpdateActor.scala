package actors

import akka.actor.Actor
import play.api.Logging
import services.MemPoolWatcherService

/** Mixin for actors which process `TxUpdate` events from the mem pool.
  */
trait TxUpdateActor { env: Actor with Logging =>

  val memPoolWatcher: MemPoolWatcherService

  def registerWithWatcher(): Unit = {
    logger.info("Registering new mem pool listener... ")
    memPoolWatcher.addListener(self)
    logger.info("registration complete.")
  }
}
