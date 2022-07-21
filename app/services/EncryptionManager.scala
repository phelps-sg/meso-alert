package services

import actors.EncryptionActor.{Decrypted, Encrypt, Encrypted, Init}
import akka.actor.ActorRef
import com.google.inject.name.Named
import com.google.inject.{ImplementedBy, Inject, Singleton}
import play.api.{Configuration, Logging}
import util.FutureInitialisingComponent

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[SodiumEncryptionManager])
trait EncryptionManagerService {
  def encrypt(plainText: Array[Byte]): Future[Encrypted]
  def decrypt(encrypted: Encrypted): Future[Decrypted]
}

@Singleton
class SodiumEncryptionManager @Inject() (@Named("encryption-actor") val actor: ActorRef, val config: Configuration)
  (implicit val executionContext: ExecutionContext)
  extends EncryptionManagerService with ActorBackend with Logging with FutureInitialisingComponent {

  initialise()

  private def secretBase64: String = config.get[String]("sodium.secret")
  private def secret: Array[Byte] = decode(secretBase64)

  private def decode(base64: String): Array[Byte] = java.util.Base64.getDecoder.decode(base64)

  override def initialiseFuture(): Future[Unit] = sendAndReceive(Init(secret))
  def encrypt(plainText: Array[Byte]): Future[Encrypted] = sendAndReceive(Encrypt(plainText))
  def decrypt(encrypted: Encrypted): Future[Decrypted] = sendAndReceive(encrypted)

}
