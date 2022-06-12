package actors

import akka.actor.{Actor, ActorRef, PoisonPill}
import akka.pattern.pipe
import dao.{DuplicateHookException, Filter, HookDao}
import org.slf4j.Logger
import play.api.libs.concurrent.InjectedActorSupport
import slick.DatabaseExecutionContext

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

trait HooksManagerActor[X, Y] extends Actor with InjectedActorSupport {

  val logger: Logger
  val dao: HookDao[X, Y]
  val messagingActorFactory: HookActorFactory[X]
  val filteringActorFactory: TxFilterNoAuthActor.Factory
  val databaseExecutionContext: DatabaseExecutionContext
  val hookTypePrefix: String

  var actors: Map[X, Array[ActorRef]] = Map()

  implicit val ec: ExecutionContext = databaseExecutionContext

  def encodeKey(key: X): String

  implicit class HookFor(key: X) {
    def withHook[R](fn: Y => R): Unit = {
      dao.find(key) map {
        case Some(hook) => Success(fn(hook))
        case None => Failure(HookNotRegisteredException(key))
      } pipeTo sender
    }
  }

  def fail(ex: Exception): Unit = {
    sender ! Failure(ex)
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
      } pipeTo sender

    case Start(uri: X) =>
      logger.debug(s"Received start request for $uri")
      provided(!(actors contains uri), uri withHook (hook => {
        self ! CreateActors(uri, hook)
        Started(hook)
      }), HookAlreadyStartedException(uri))

    case Stop(uri: X) =>
      provided (actors contains uri, {
        actors(uri).foreach(_ ! PoisonPill)
        actors -= uri
        uri withHook (hook => Stopped(hook))
      }, HookNotStartedException(uri))

    case CreateActors(key: X, hook: Filter) =>
      val actorId = encodeKey(key)
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