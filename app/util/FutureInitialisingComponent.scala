package util

import play.api.Logging

import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{Await, ExecutionContext, Future}
import scala.util.{Failure, Success}

/**
 * A mixin for any class which performs initialisation in a Future, e.g. querying the database.
 * The implementing class should call {{{initialise()}}} in its constructor, and should
 * have a binding in the Guice Module that specifies eager binding, e.g.:
 *
 * {{{
 *  class Module extends AbstractModule with AkkaGuiceSupport {
 *    bind(classOf[MemPoolWatcherService]).to(classOf[MemPoolWatcher]).asEagerSingleton()
 *  }
 * }}}
 *
 * The component will then be initialised synchronously; i.e. the main thread will block
 * waiting for the initialisation to complete within the {{{initialisationTimeout}}}.
 *
 * If the initialisation fails for any reason this is considered a fatal error and the
 * underlying cause is wrapped in a RuntimeException which is then thrown from
 * {{{initialise()}}}.
 */
trait FutureInitialisingComponent extends Logging {

  protected val initialisationTimeout: FiniteDuration = 1.minute
  implicit val ec: ExecutionContext

  /**
   * Implementors should override this method with their initialisation code
   * @return  A future containing the result of the initialisation
   */
  protected def initialiseFuture(): Future[Unit]

  /**
   * Implementors should call this method in their constructor.
   */
  def initialise(): Unit = {
    logger.debug("Initialising.. ")
    val f = initialiseFuture()
    f.onComplete {
      case Success(_) => logger.info("Initialisation complete.")
      case Failure(ex) =>
        logger.error(s"Initialisation failed with ${ex.getMessage}")
        ex.printStackTrace()
        throw new RuntimeException(ex)
    }
    Await.ready(f, initialisationTimeout)
  }
}
