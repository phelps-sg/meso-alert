package services

import actors.EncryptionActor.{Decrypted, Encrypt, Encrypted, Init}
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.google.inject.name.Named
import play.api.{Configuration, Logging}

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[SodiumEncryptionManager])
trait EncryptionManagerService {
  def init(): Future[Int]
  def encrypt(plainText: Array[Byte]): Future[Encrypted]
  def decrypt(encrypted: Encrypted): Future[Decrypted]
}

@Singleton
class SodiumEncryptionManager @Inject() (@Named("encryption-actor") val actor: ActorRef, val config: Configuration)
  (implicit system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends EncryptionManagerService with ActorBackend with Logging {

  private val secretBase64 = config.get[String]("sodium.secret")
  private val secret = decode(secretBase64)

  private def decode(base64: String): Array[Byte] = java.util.Base64.getDecoder.decode(base64)

  def init(): Future[Int] = sendAndReceive(Init(secret))
  def encrypt(plainText: Array[Byte]): Future[Encrypted] = sendAndReceive(Encrypt(plainText))
  def decrypt(encrypted: Encrypted): Future[Decrypted] = sendAndReceive(encrypted)

}
