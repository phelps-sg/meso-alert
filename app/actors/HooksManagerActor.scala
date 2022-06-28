package actors

import akka.actor.{Actor, ActorRef, PoisonPill}
import akka.pattern.pipe
import dao.{DuplicateHookException, Filter, Hook, HookDao}
import play.api.Logger
import play.api.libs.concurrent.InjectedActorSupport
import slick.DatabaseExecutionContext

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

trait TxMessagingActorFactory[X] {
  def apply(x: X): Actor
}

object HooksManagerActor {
  case class CreateActors[X](uri: X, hook: Hook[X])
}

trait HooksManagerActor[X, Y <: Hook[X]] extends Actor with InjectedActorSupport {

  import HooksManagerActor._

  protected val logger: Logger
  val dao: HookDao[X, Y]
  val messagingActorFactory: TxMessagingActorFactory[X]
  val filteringActorFactory: TxFilterActor.Factory
  val databaseExecutionContext: DatabaseExecutionContext
  val hookTypePrefix: String

  var actors: Map[X, Array[ActorRef]] = Map()

  implicit val ec: ExecutionContext = databaseExecutionContext

  def encodeKey(key: X): String

  implicit class HookFor(key: X) {
    def withHook[R](fn: Hook[X] => R): Unit = {
      dao.find(key) map {
        case Some(hook) => Success(fn(hook))
        case None => Failure(HookNotRegisteredException(key))
      } pipeTo sender()
    }
  }

  def fail(ex: Exception): Unit = {
    sender() ! Failure(ex)
  }

  def provided(condition: => Boolean, block: => Unit, ex: => Exception): Unit = {
    if (condition) block else fail(ex)
  }

  override def receive: Receive = {

    case Register(hook: Y) =>
      dao.insert(hook) map {
        _ => Success(Registered(hook))
      } recover {
        case DuplicateHookException(_) => Failure(HookAlreadyRegisteredException(hook))
      } pipeTo sender()

    case Update(newHook: Y) =>
      dao.update(newHook) map {
        _ => Success(Updated(newHook))
      } pipeTo sender()

    case Start(uri: X) =>
      logger.debug(s"Received start request for $uri")
      provided(!(actors contains uri), uri withHook (hook => {
        self ! CreateActors(uri, hook)
        val startedHook = hook.newStatus(isRunning = true)
        self ! Update(startedHook)
        Started(startedHook)
      }), HookAlreadyStartedException(uri))

    case Stop(key: X) =>
      logger.debug(s"Stopping actor with key $key")
      provided (actors contains key, {
        actors(key).foreach(_ ! PoisonPill)
        actors -= key
        key withHook (hook => {
          self ! Update(hook.newStatus(isRunning = false))
          Stopped(hook)
        })
      }, HookNotStartedException(key))

    case CreateActors(key: X, hook: Filter) =>
      logger.debug(s"Creating child actors for key $key and hook $hook")
      val actorId = encodeKey(key)
      logger.debug(s"actorId = $actorId")
      val messagingActor =
        injectedChild(messagingActorFactory(key), name = s"$hookTypePrefix-messenger-$actorId")
      val filteringActor =
        injectedChild(filteringActorFactory(messagingActor, hook.filter),
          name = s"$hookTypePrefix-filter-$actorId")
      actors += key -> Array(messagingActor, filteringActor)

    case CreateActors(key: X, _) =>
      logger.error(s"Not starting child actors; unrecognized hook type")

  }

}
