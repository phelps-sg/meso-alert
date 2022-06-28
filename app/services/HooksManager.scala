package services

import actors.{Register, Registered, Start, Started, Stop, Stopped, Update, Updated}
import akka.actor.{ActorRef, ActorSystem}
import dao.{Hook, HookDao}
import play.api.Logging

import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success}

trait HooksManagerService[X, Y] {
  def init(): Future[Seq[Started[Y]]]
  def start(key: X): Future[Started[Y]]
  def stop(key: X): Future[Stopped[Y]]
  def register(hook: Y): Future[Registered[Y]]
}

trait HooksManager[X, Y <: Hook[X]] extends ActorBackend with Logging {

  val hookDao: HookDao[X, Y]
  val actor: ActorRef

  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext

  def init(): Future[Seq[Started[Y]]] = {

    val initFuture = for {
      _ <- hookDao.init()
      keys <- hookDao.allRunningKeys()
      started <- Future.sequence(keys.map(key => start(key)))
    } yield started

    initFuture.onComplete {
      case Success(x) => logger.info(f"Started ${x.size} hooks.")
      case Failure(exception) => logger.error(f"Failed to load hooks: ${exception.getMessage}")
    }

    initFuture
  }

  def start(key: X): Future[Started[Y]] = sendAndReceive(Start(key))
  def stop(key: X): Future[Stopped[Y]] = sendAndReceive(Stop(key))
  def register(hook: Y): Future[Registered[Y]] = sendAndReceive(Register(hook))
  def update(hook: Y): Future[Updated[Y]] = sendAndReceive(Update(hook))
}
