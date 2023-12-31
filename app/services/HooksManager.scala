package services

import actors.{
  Register,
  Registered,
  Start,
  Started,
  Stop,
  Stopped,
  Update,
  Updated
}
import akka.actor.{ActorRef, ActorSystem}
import dao.{Hook, HookDao}
import play.api.Logging
import util.FutureInitialisingComponent

import scala.annotation.unused
import scala.concurrent.{ExecutionContext, Future}

trait HooksManagerService[X, Y] {
  def start(@unused key: X): Future[Started[Y]]
  def stop(@unused key: X): Future[Stopped[Y]]
  def register(@unused hook: Y): Future[Registered[Y]]
}

trait HooksManager[X, Y <: Hook[X]]
    extends ActorBackend
    with Logging
    with FutureInitialisingComponent {

  val hookDao: HookDao[X, Y]
  val actor: ActorRef

  implicit val system: ActorSystem
  implicit val executionContext: ExecutionContext

  override def initialiseFuture(): Future[Unit] = {

    val initFuture = for {
      keys <- hookDao.allRunningKeys()
      started <- Future.sequence(keys.map(start))
      _ <- Future.successful {
        logger.info(f"Started ${started.size} hooks.")
      }
    } yield ()

    initFuture.recover { case exception: Exception =>
      logger.error(f"Failed to load hooks: ${exception.getMessage}")
    }

    initFuture
  }

  def start(key: X): Future[Started[Y]] = sendAndReceive[Start[X], Started[Y]] {
    Start(key)
  }

  def stop(key: X): Future[Stopped[Y]] = sendAndReceive[Stop[X], Stopped[Y]] {
    Stop(key)
  }

  def register(hook: Y): Future[Registered[Y]] =
    sendAndReceive[Register[X], Registered[Y]] {
      Register(hook)
    }

  def update(hook: Y): Future[Updated[Y]] =
    sendAndReceive[Update[X], Updated[Y]] {
      Update(hook)
    }

}
