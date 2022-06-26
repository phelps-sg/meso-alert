package actors

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model.ws.TextMessage
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json._
import services.{InvalidCredentialsException, MemPoolWatcherService, UserManagerService}

//noinspection TypeAnnotation
object TxAuthActor {

  trait Factory {
    def apply(out: ActorRef): Actor
  }

  def props(out: ActorRef, memPoolWatcher: MemPoolWatcherService, userManager: UserManagerService)
           (implicit system: ActorSystem): Props =
    Props(new TxAuthActor(out, memPoolWatcher, userManager))

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

  implicit val authReads: Reads[Auth] =
    ((JsPath \ "id").read[String] and (JsPath \ "token").read[String])(Auth.apply _)

}

//noinspection TypeAnnotation
class TxAuthActor @Inject()(@Assisted val out: ActorRef, val memPoolWatcher: MemPoolWatcherService,
                            userManager: UserManagerService)(implicit system: ActorSystem)
  extends Actor with TxUpdateActor {

  override val logger: Logger = LoggerFactory.getLogger(classOf[TxAuthActor])

  import TxAuthActor._

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
      val filterActor = system.actorOf(TxFilterActor.props(out, user.filter, memPoolWatcher))
      def authorized: Receive = deathHandler.orElse {
        message => filterActor ! message
      }
      context.become(authorized)
      registerWithWatcher()
    } catch {
      case _: InvalidCredentialsException =>
        self ! Die(s"Authentication failed for ${auth.id}.")
    }
  }

}
