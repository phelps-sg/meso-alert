package actors

import akka.actor.{Actor, ActorRef, Props}
import com.github.nscala_time.time.Imports.DateTime
import daemon.MemPoolWatcher
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsObject, JsPath, Json, Reads, Writes}

//noinspection TypeAnnotation
object TxWatchActor {

  def props(out: ActorRef): Props = Props(new TxWatchActor(out))

  case class TxUpdate(hash: String, value: Long, time: DateTime)
  case class Auth(id: String, token: String)

  implicit val txUpdateWrites = new Writes[TxUpdate] {
    def writes(tx: TxUpdate): JsObject = Json.obj(
      "hash" -> tx.hash,
      "value" -> tx.value,
      "time" -> tx.time.toString()
    )
  }

  implicit val authReads: Reads[Auth] =
    ((JsPath \ "id").read[String] and (JsPath \ "token").read[String])(Auth.apply _)

}

//noinspection TypeAnnotation
class TxWatchActor(out: ActorRef) extends Actor {

  private val log: Logger = LoggerFactory.getLogger(classOf[TxWatchActor])

  import TxWatchActor._

  override def preStart(): Unit = {
    log.info("Registering new mem pool listener... ")
    MemPoolWatcher.addListener(self)
    log.info("registration complete.")
  }

  def receive = {
    case txUpdate: TxUpdate =>
      out ! txUpdate
    case auth: Auth =>
      log.info(s"Received auth request for id ${auth.id}")
    case x =>
      log.warn(s"Unrecognized message $x")
  }

}
