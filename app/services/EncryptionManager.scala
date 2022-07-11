package services

import actors.EncryptionActor.{Decrypted, Encrypt, Encrypted, Init}
import akka.actor.{ActorRef, ActorSystem}
import com.google.inject.name.Named
import com.google.inject.{ImplementedBy, Inject, Singleton}
import controllers.InitialisingController
import play.api.{Configuration, Logging}

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[SodiumEncryptionManager])
trait EncryptionManagerService {
  def init(): Future[Unit]
  def encrypt(plainText: Array[Byte]): Future[Encrypted]
  def decrypt(encrypted: Encrypted): Future[Decrypted]
}

@Singleton
class SodiumEncryptionManager @Inject() (@Named("encryption-actor") val actor: ActorRef, val config: Configuration)
  (implicit system: ActorSystem, implicit val executionContext: ExecutionContext)
  extends EncryptionManagerService with ActorBackend with Logging with InitialisingController {

  private val secretBase64 = config.get[String]("sodium.secret")
  private val secret = decode(secretBase64)

  init()

  private def decode(base64: String): Array[Byte] = java.util.Base64.getDecoder.decode(base64)

  override def init(): Future[Unit] = sendAndReceive(Init(secret))
  def encrypt(plainText: Array[Byte]): Future[Encrypted] = sendAndReceive(Encrypt(plainText))
  def decrypt(encrypted: Encrypted): Future[Decrypted] = sendAndReceive(encrypted)

}
