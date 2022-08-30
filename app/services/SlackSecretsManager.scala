package services

import actors.{GenerateSecret, ValidSecret, VerifySecret}
import akka.actor.ActorRef
import com.google.inject.name.Named
import com.google.inject.{ImplementedBy, Inject}
import dao.{Secret, UserId}
import slick.EncryptionExecutionContext

import scala.concurrent.Future

@ImplementedBy(classOf[SlackSecretsManager])
trait SlackSecretsManagerService {
  def generateSecret(userId: UserId): Future[Secret]
  def verifySecret(userId: UserId, secret: Secret): Future[ValidSecret]
}

class SlackSecretsManager @Inject() (
    @Named("slack-secrets-actor") val actor: ActorRef,
    val executionContext: EncryptionExecutionContext
) extends SlackSecretsManagerService
    with ActorBackend {

  override def generateSecret(userId: UserId): Future[Secret] = sendAndReceive {
    GenerateSecret(userId)
  }

  override def verifySecret(userId: UserId, secret: Secret): Future[ValidSecret] = sendAndReceive {
    VerifySecret(userId, secret)
  }

}
