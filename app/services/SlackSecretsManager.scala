package services

import actors.SlackSecretsActor.{
  GenerateSecret,
  Unbind,
  ValidSecret,
  VerifySecret
}
import akka.actor.ActorRef
import com.google.inject.name.Named
import com.google.inject.{ImplementedBy, Inject}
import dao.{Secret, UserId}
import slick.EncryptionExecutionContext

import scala.concurrent.Future

/** Generates per-user secrets for use during Slack V2 OAuth2 authorisation when
  * installing the app to a Slack Workspace in order to prevent forgery attacks
  * by unauthorised users. The secret can be passed as part of the state
  * parameter when
  * [[https://api.slack.com/authentication/oauth-v2#asking asking for scopes]],
  * which is then passed back to [[controllers.SlackAuthController]] when Slack
  * redirects the user to the
  * [[https://api.slack.com/authentication/oauth-v2#exchanging Redirect URL]].
  */
@ImplementedBy(classOf[SlackSecretsManager])
trait SlackSecretsManagerService {
  def unbind(uid: UserId): Future[Unbind]
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

  override def verifySecret(
      userId: UserId,
      secret: Secret
  ): Future[ValidSecret] = sendAndReceive {
    VerifySecret(userId, secret)
  }

  override def unbind(userId: UserId): Future[Unbind] = sendAndReceive {
    Unbind(userId)
  }

}
