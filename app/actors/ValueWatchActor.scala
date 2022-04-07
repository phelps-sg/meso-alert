package actors

import akka.actor.{Actor, ActorRef, Props}
import daemon.MemPoolWatcher

import scala.concurrent.Future
import scala.concurrent.ExecutionContext.Implicits.global

object ValueWatchActor {
  def props(out: ActorRef): Props = Props(new ValueWatchActor(out))

  case class TxUpdate(hash: String, value: Long)

}

//noinspection TypeAnnotation
class ValueWatchActor(out: ActorRef) extends Actor {

  val daemon = new MemPoolWatcher(this.self)

  override def preStart(): Unit = {
    daemon.startDaemon()
  }

  def receive = {
    case msg: String =>
      out ! s"I received your message: $msg"
    case txUpdate: ValueWatchActor.TxUpdate =>
      out ! s"${txUpdate.hash}: ${txUpdate.value}"
  }

}
