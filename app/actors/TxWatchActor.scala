package actors

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import com.github.nscala_time.time.Imports.DateTime
import services.MemPoolWatcher
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json.{JsObject, JsPath, Json, Reads, Writes}

//noinspection TypeAnnotation
object TxWatchActor {

  def props(out: ActorRef): Props = Props(new TxWatchActor(out))

  case class TxUpdate(hash: String, value: Long, time: DateTime, isPending: Boolean)
  case class Auth(id: String, token: String)
  case class Die(message: String)

  implicit val txUpdateWrites = new Writes[TxUpdate] {
    def writes(tx: TxUpdate): JsObject = Json.obj(
      "hash" -> tx.hash,
      "value" -> tx.value,
      "time" -> tx.time.toString(),
      "isPending" -> tx.isPending
    )
  }

  implicit val authReads: Reads[Auth] =
    ((JsPath \ "id").read[String] and (JsPath \ "token").read[String])(Auth.apply _)

}

//noinspection TypeAnnotation
class TxWatchActor(out: ActorRef) extends Actor {

  private val log: Logger = LoggerFactory.getLogger(classOf[TxWatchActor])

  import TxWatchActor._

  def registerWithWatcher(): Unit = {
    log.info("Registering new mem pool listener... ")
    MemPoolWatcher.addListener(self)
    log.info("registration complete.")
  }

  override def receive: Receive = unauthorized

  private def deathHandler: Receive = {
    case Die(reason) =>
      log.info(s"Died due to reason: $reason")
      self ! PoisonPill
  }

  def authorized: Receive = {
    case txUpdate: TxUpdate =>
      out ! txUpdate
  }

  def unauthorized: Receive = deathHandler.orElse {
    case auth: Auth =>
      log.info(s"Received auth request for id ${auth.id}")
      authenticate(auth)
    case x =>
      log.warn(s"Unrecognized message $x")
  }

  def authenticate(auth: Auth) = {
    if (auth.id == "guest") {
      context.become(authorized)
      registerWithWatcher()
    } else {
        self ! Die("Invalid token!")
    }
  }

}
