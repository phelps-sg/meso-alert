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

  implicit class HookFor(key: X) {
    def withHook[R](fn: Hook[X] => R): Unit = {
      dao.find(key) map { hook =>
        Success(fn(hook))
      } recover { case _: NoSuchElementException =>
        Failure(HookNotRegisteredException(key))
      } pipeTo sender()
    }
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

    case Start(uri: X) =>
      logger.debug(s"Received start request for $uri")
      if (!(actors contains uri)) {
        uri withHook (hook => {
          self ! CreateActors(uri, hook)
          val startedHook = hook.newStatus(isRunning = true)
          self ! Update(startedHook)
          Started(startedHook)
        })
      } else fail(HookAlreadyStartedException(uri))

    case Stop(key: X) =>
      logger.debug(s"Stopping actor with key $key")
      if (actors contains key) {
        actors(key).foreach(_ ! PoisonPill)
        actors -= key
        key withHook (hook => {
          self ! Update(hook.newStatus(isRunning = false))
          Stopped(hook)
        })
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
