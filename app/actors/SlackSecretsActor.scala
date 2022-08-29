package actors
import actors.MessageHandlers.UnRecognizedMessageHandlerWithBounce
import akka.pattern.pipe
import akka.persistence._
import com.google.inject.Inject
import dao.{Secret, UserId}
import play.api.Logging
import services.EncryptionManagerService
import slick.EncryptionExecutionContext

import scala.util.Success

sealed trait SlackSecretsCommand
case class GenerateSecret(userId: UserId) extends SlackSecretsCommand
case class Unbind(userId: UserId) extends SlackSecretsCommand

sealed trait SlackSecretsEvent
case class BindEvent(userId: UserId, secret: Secret) extends SlackSecretsEvent
case class UnbindEvent(userId: UserId) extends SlackSecretsEvent

case class SecretsState(mapping: Map[UserId, Secret]) {

  def updated(evt: SlackSecretsEvent): SecretsState = {
    evt match {
      case BindEvent(userId, secret) =>
        SecretsState(
          mapping + (userId -> secret)
        )
      case UnbindEvent(userId) =>
        SecretsState(
          mapping - userId
        )
    }
  }

  def size: Int = mapping.size
}

class SlackSecretsActor @Inject() (
    protected val encryptionManagerService: EncryptionManagerService,
    protected val encryptionManagerExecutionContext: EncryptionExecutionContext
) extends PersistentActor
    with Logging
    with UnRecognizedMessageHandlerWithBounce {

  override def persistenceId = "slack-secrets-actor-singleton"

  val slackSecretSize: Int = 64
  val snapShotInterval: Int = 1000

  var state: SecretsState = SecretsState(Map())

  def updateState(event: SlackSecretsEvent): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: SlackSecretsEvent                   => updateState(evt)
    case SnapshotOffer(_, snapshot: SecretsState) => state = snapshot
  }

  def handleEvent(event: SlackSecretsEvent): Unit = {
    persist(event) { event =>
      updateState(event)
      context.system.eventStream.publish(event)
      if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
        saveSnapshot(state)
    }
  }

  val receiveCommand: Receive = {

    case ev: SlackSecretsCommand =>
      ev match {

        case Unbind(userId) =>
          handleEvent(UnbindEvent(userId))
          sender() ! Success(Unbind(userId))

        case GenerateSecret(userId) =>
          implicit val ec = encryptionManagerExecutionContext
          encryptionManagerService.generateSecret(slackSecretSize) map {
            secret =>
              handleEvent(BindEvent(userId, secret))
              Success(secret)
          } pipeTo sender()
      }

    case message => unrecognizedMessage(message)
  }

}
