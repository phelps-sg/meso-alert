package actors

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.http.scaladsl.model.ws.TextMessage
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json._
import services.{InvalidCredentialsException, MemPoolWatcherService, UserManagerService}

import scala.collection.immutable.ArraySeq

//noinspection TypeAnnotation
object TxFilterActor {

  def props(out: ActorRef, memPoolWatcher: MemPoolWatcherService, userManager: UserManagerService): Props =
    Props(new TxFilterActor(out, memPoolWatcher, userManager))

  case class TxInputOutput(address: Option[String], value: Option[Long])

  case class Auth(id: String, token: String) {
    def message: TextMessage.Strict = TextMessage(authWrites.writes(this).toString())
  }
  case class Die(message: String)

  implicit val authWrites = new Writes[Auth] {
    def writes(auth: Auth): JsObject = Json.obj(
      "id" -> auth.id,
      "token" -> auth.token
    )
  }

  implicit val txInputOutputWrites = new Writes[TxInputOutput] {
    def writes(inpOut: TxInputOutput): JsObject = {
      val addressField: Array[(String, Json.JsValueWrapper)] =
        Array(inpOut.address).filterNot(_.isEmpty).map("address" -> _.get)
      val valueField: Array[(String, Json.JsValueWrapper)] =
        Array(inpOut.value).filterNot(_.isEmpty).map("value" -> _.get)
      Json.obj(ArraySeq.unsafeWrapArray(addressField ++ valueField): _*)
    }
  }

  implicit val authReads: Reads[Auth] =
    ((JsPath \ "id").read[String] and (JsPath \ "token").read[String])(Auth.apply _)

}

//noinspection TypeAnnotation
class TxFilterActor(out: ActorRef, memPoolWatcher: MemPoolWatcherService, userManager: UserManagerService)
  extends AbstractTxUpdateActor(memPoolWatcher) {

  private val logger: Logger = LoggerFactory.getLogger(classOf[TxFilterActor])

  import TxFilterActor._

  override def receive: Receive = unauthorized

  private def deathHandler: Receive = {
    case Die(reason) =>
      logger.info(s"Died due to reason: $reason")
      self ! PoisonPill
  }

  def unauthorized: Receive = deathHandler.orElse {
    case auth: Auth =>
      logger.info(s"Received auth request for id ${auth.id}")
      authenticate(auth)
    case x =>
      logger.warn(s"Unrecognized message $x")
  }

  def authenticate(auth: Auth): Unit = {
    try {
      val user = userManager.authenticate(auth.id)

      def authorized: Receive = deathHandler.orElse {
        case txUpdate: TxUpdate =>
          if (user.filter(txUpdate)) out ! txUpdate
      }

      context.become(authorized)
      registerWithWatcher()
    } catch {
      case _: InvalidCredentialsException =>
        self ! Die(s"Authentication failed for ${auth.id}.")
    }
  }

}
