package actors

import akka.actor.{Actor, ActorRef, Props}

object ValueWatchActor {
  def props(out: ActorRef): Props = Props(new ValueWatchActor(out))
}

//noinspection TypeAnnotation
class ValueWatchActor(out: ActorRef) extends Actor {

  def receive = {
    case msg: String =>
      out ! s"I received your message: $msg"
  }

}
