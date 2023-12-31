package actors

import actors.MessageHandlers.UnrecognizedMessageHandlerFatal
import akka.actor.{Actor, ActorRef, PoisonPill}
import akka.pattern.pipe
import dao.{DuplicateKeyException, Hook, HookDao}
import play.api.Logging
import play.api.libs.concurrent.InjectedActorSupport
import slick.DatabaseExecutionContext

import java.time.Clock
import scala.concurrent.ExecutionContext
import scala.reflect.ClassTag
import scala.util.{Failure, Success}

trait TxMessagingActorFactory[Y] {
  def apply(x: Y): Actor
}

object HooksManagerActor {
  case class CreateActors[X](uri: X, hook: Hook[X])
}

/** Abstract superclass for actors which manage hooks. A hook is a subscription
  * for blockchain events over a particular channel.
  * @tparam X
  *   The type of the key for the hook, typically a channel id
  * @tparam Y
  *   The type for the hook
  */
abstract class HooksManagerActor[X: ClassTag, Y <: Hook[X]: ClassTag]
    extends Actor
    with InjectedActorSupport
    with Logging
    with UnrecognizedMessageHandlerFatal {

  import HooksManagerActor._

  val clock: Clock
  val dao: HookDao[X, Y]
  val messagingActorFactory: TxMessagingActorFactory[Y]
  val filteringActorFactory: TxFilterActor.Factory
  val batchingActorFactory: RateLimitingBatchingActor.Factory
  val databaseExecutionContext: DatabaseExecutionContext
  val hookTypePrefix: String

  var actors: Map[X, Array[ActorRef]] = Map()

  implicit val ec: ExecutionContext = databaseExecutionContext

  def encodeKey(key: X): String

  def withHookFor[R](key: X)(fn: Hook[X] => R): Unit = {
    dao.find(key) map { hook =>
      Success(fn(hook))
    } recover { case _: NoSuchElementException =>
      Failure(HookNotRegisteredException(key))
    } pipeTo sender()
  }

  def fail(ex: Exception): Unit = {
    sender() ! Failure(ex)
  }

  override def receive: Receive = {

    case Register(hook: Y) =>
      dao.insert(hook) map { _ =>
        Success(Registered(hook))
      } recover { case DuplicateKeyException(_) =>
        Failure(HookAlreadyRegisteredException(hook))
      } pipeTo sender()

    case Update(newHook: Y) =>
      dao.update(newHook) map { _ =>
        Success(Updated(newHook))
      } pipeTo sender()

    case Start(key: X) =>
      logger.debug(s"Received start request for $key")
      if (!(actors contains key)) {
        withHookFor(key) { hook =>
          {
            self ! CreateActors(key, hook)
            val startedHook = hook.newStatus(isRunning = true)
            self ! Update(startedHook)
            Started(startedHook)
          }
        }
      } else fail(HookAlreadyStartedException(key))

    case Stop(key: X) =>
      logger.debug(s"Stopping actor with key $key")
      if (actors contains key) {
        actors(key).foreach(_ ! PoisonPill)
        actors -= key
        withHookFor(key) { hook =>
          {
            self ! Update(hook.newStatus(isRunning = false))
            Stopped(hook)
          }
        }
      } else fail(HookNotStartedException(key))

    case CreateActors(key: X, hook: Y) =>
      logger.debug(s"Creating child actors for key $key and hook $hook")
      val actorId = encodeKey(key)
      logger.debug(s"actorId = $actorId")
      val messagingActor =
        injectedChild(
          messagingActorFactory(hook),
          name = s"$hookTypePrefix-messenger-$actorId"
        )
      val batchingActor =
        injectedChild(
          batchingActorFactory(messagingActor, clock),
          name = s"$hookTypePrefix-batcher-$actorId"
        )
      val filteringActor =
        injectedChild(
          filteringActorFactory(batchingActor, hook.filter),
          name = s"$hookTypePrefix-filter-$actorId"
        )
      actors += key -> Array(messagingActor, batchingActor, filteringActor)

    case CreateActors(_: X, _) =>
      logger.error("Not starting child actors; unrecognized hook type")

    case Success(Updated(hook)) =>
      logger.debug(s"Successfully updated $hook")

    case x =>
      unrecognizedMessage(x)

  }

}
