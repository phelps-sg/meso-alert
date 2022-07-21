package actors

import akka.actor.{Actor, ActorRef, ActorSystem, PoisonPill, Props}
import akka.http.scaladsl.model.ws.TextMessage
import com.google.inject.Inject
import com.google.inject.assistedinject.Assisted
import play.api.Logging
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json._
import services.{InvalidCredentialsException, MemPoolWatcherService, UserManagerService}

import scala.util.{Failure, Success}

//noinspection TypeAnnotation
object AuthenticationActor {

  trait Factory {
    def apply(out: ActorRef): Actor
  }

  def props(out: ActorRef, memPoolWatcher: MemPoolWatcherService, userManager: UserManagerService)
           (implicit system: ActorSystem): Props =
    Props(new AuthenticationActor(out, memPoolWatcher, userManager))

  case class TxInputOutput(address: Option[String], value: Option[Long])

  case class Auth(id: String, token: String) {
    def message: TextMessage.Strict = TextMessage(authWrites.writes(this).toString())
  }
  case class Die(message: String)

  // scalafix:off
  implicit val authWrites = new Writes[Auth] {
    def writes(auth: Auth): JsObject = Json.obj(
      "id" -> auth.id,
      "token" -> auth.token
    )
  }
  // scalafix:on

  implicit val authReads: Reads[Auth] =
    ((JsPath \ "id").read[String] and (JsPath \ "token").read[String])(Auth.apply _)

}

//noinspection TypeAnnotation
class AuthenticationActor @Inject()(@Assisted val out: ActorRef, val memPoolWatcher: MemPoolWatcherService,
                                    userManager: UserManagerService)(implicit system: ActorSystem)
  extends Actor with TxUpdateActor with UnrecognizedMessageHandler with Logging {

  import AuthenticationActor._

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
      unrecognizedMessage(x)
  }

  def authenticate(auth: Auth): Unit = {
    userManager.authenticate(auth.id) match {

      case Success(user) =>
        val filterActor = system.actorOf(TxFilterActor.props(out, user.filter, memPoolWatcher))
        def authorized: Receive = deathHandler.orElse {
          message => filterActor ! message
        }
        context.become(authorized)

      case Failure(InvalidCredentialsException) =>
        self ! Die(s"Authentication failed for ${auth.id}.")

      case Failure(ex: Exception) =>
        self ! Die(ex.getMessage)

      case x =>
        unrecognizedMessage(x)
    }
  }

}
