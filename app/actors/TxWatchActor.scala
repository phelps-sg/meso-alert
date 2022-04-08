package actors


import akka.actor.{Actor, ActorRef, Props}
import com.github.nscala_time.time.Imports.DateTime
import daemon.MemPoolWatcher
import play.api.libs.json.{JsObject, Json, Writes}

//noinspection TypeAnnotation
object TxWatchActor {

  def props(out: ActorRef): Props = Props(new TxWatchActor(out))

  case class TxUpdate(hash: String, value: Long, time: DateTime)

  implicit val txUpdateWrites = new Writes[TxUpdate] {
    def writes(tx: TxUpdate): JsObject = Json.obj(
      "hash" -> tx.hash,
      "value" -> tx.value,
      "time" -> tx.time.toString()
    )
  }
}

//noinspection TypeAnnotation
class TxWatchActor(out: ActorRef) extends Actor {

  val daemon = new MemPoolWatcher(this.self)

  override def preStart(): Unit = {
    daemon.startDaemon()
  }

  def receive = {
    case txUpdate: TxWatchActor.TxUpdate =>
      out ! txUpdate
  }

}
