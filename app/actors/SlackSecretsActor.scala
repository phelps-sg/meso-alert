package actors
import actors.MessageHandlers.UnRecognizedMessageHandlerWithBounce
import akka.actor.{ActorRef, Props}
import akka.persistence._
import com.google.inject.Inject
import dao.{RegisteredUserId, Secret}
import play.api.Logging
import services.EncryptionManagerService
import slick.EncryptionExecutionContext
import util.Encodings.base64Encode

import scala.concurrent.ExecutionContext
import scala.util.{Failure, Success}

/** An actor which generates temporary secrets for Slack authorisation when
  * [[https://api.slack.com/authentication/oauth-v2#exchanging installing the app to a Slack workspace]].
  * Each secret is associated with a Slack user ID, and the mapping is persisted
  * to ensure that Slack authorisation can recover in the case of a temporary
  * outage.
  */
object SlackSecretsActor {

  final case class InvalidSecretException(id: RegisteredUserId, secret: Secret)
      extends Exception(s"Invalid secret: ${base64Encode(secret.data)} for $id")

  final case class ValidSecret(id: RegisteredUserId)

  sealed trait SlackSecretsCommand
  final case class GenerateSecret(userId: RegisteredUserId)
      extends SlackSecretsCommand
  final case class RecordSecret(
      userId: RegisteredUserId,
      secret: Secret,
      replyTo: ActorRef
  ) extends SlackSecretsCommand
  final case class Unbind(userId: RegisteredUserId) extends SlackSecretsCommand
  final case class VerifySecret(userId: RegisteredUserId, secret: Secret)
      extends SlackSecretsCommand

  sealed trait SlackSecretsEvent
  final case class BindEvent(userId: RegisteredUserId, secret: Secret)
      extends SlackSecretsEvent
  final case class UnbindEvent(userId: RegisteredUserId)
      extends SlackSecretsEvent

  final case class SecretsState(mapping: Map[RegisteredUserId, Secret]) {

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
  def props(
      encryptionManagerService: EncryptionManagerService,
      encryptionExecutionContext: EncryptionExecutionContext
  ): Props = Props(
    new SlackSecretsActor(encryptionManagerService, encryptionExecutionContext)
  )
}

class SlackSecretsActor @Inject() (
    protected val encryptionManagerService: EncryptionManagerService,
    protected val encryptionManagerExecutionContext: EncryptionExecutionContext
) extends PersistentActor
    with Logging
    with UnRecognizedMessageHandlerWithBounce {

  import SlackSecretsActor._

  override def persistenceId = "slack-secrets-actor-singleton"

  val slackSecretSize: Int = 64
  val snapShotInterval: Int = 1

  var state: SecretsState = SecretsState(Map())

  def updateState(event: SlackSecretsEvent): Unit =
    state = state.updated(event)

  val receiveRecover: Receive = {
    case evt: SlackSecretsEvent                   => updateState(evt)
    case SnapshotOffer(_, snapshot: SecretsState) => state = snapshot
  }

  def handleEvent(event: SlackSecretsEvent): Unit = {
    persist(event) { event =>
      logger.debug(s"Updating state with $event")
      updateState(event)
      context.system.eventStream.publish(event)
//      if (lastSequenceNr % snapShotInterval == 0 && lastSequenceNr != 0)
//        saveSnapshot(state)
    }
  }

  val receiveCommand: Receive = {

    case ev: SlackSecretsCommand =>
      ev match {

        case Unbind(userId) =>
          handleEvent(UnbindEvent(userId))
          sender() ! Success(Unbind(userId))

        case GenerateSecret(userId) =>
          logger.debug(s"Generating secret for $userId...")
          implicit val ec: ExecutionContext = encryptionManagerExecutionContext
          val replyTo = sender()
          logger.debug("replyTo = replyTo")
          encryptionManagerService.generateSecret(slackSecretSize) map {
            secret =>
              self ! RecordSecret(userId, secret, replyTo)
          } recover { case e: Exception =>
            replyTo ! Failure(e)
          }

        case RecordSecret(userId, secret, replyTo) =>
          logger.debug(s"Recording secret $secret for $userId for $replyTo... ")
          handleEvent(BindEvent(userId, secret))
          logger.debug(
            s"Generating secret for $userId: success with result $secret."
          )
          replyTo ! Success(secret)

        case VerifySecret(userId, secret) =>
          logger.debug(s"Verifying secret $secret for user $userId... ")
          if (
            (state.mapping contains userId) && (state
              .mapping(userId)
              .data sameElements secret.data)
          ) {
            logger.debug(s"Verifying secret $secret for user $userId: success.")
            sender() ! Success(ValidSecret(userId))
          } else {
            logger.info(s"Invalid secret $secret for user $userId.")
            sender() ! Failure(InvalidSecretException(userId, secret))
          }
      }

    case message => unrecognizedMessage(message)
  }

}
