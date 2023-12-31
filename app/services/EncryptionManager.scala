package services

import actors.EncryptionActor._
import akka.actor.ActorRef
import com.google.inject.name.Named
import com.google.inject.{ImplementedBy, Inject, Singleton}
import dao.Secret
import play.api.{Configuration, Logging}
import slick.EncryptionExecutionContext
import util.FutureInitialisingComponent

import scala.concurrent.Future

@ImplementedBy(classOf[SodiumEncryptionManager])
trait EncryptionManagerService {
  def encrypt(plainText: Array[Byte]): Future[Encrypted]
  def decrypt(encrypted: Encrypted): Future[Decrypted]
  def generateSecret(n: Int): Future[Secret]
}

@Singleton
class SodiumEncryptionManager @Inject() (
    @Named("encryption-actor") val actor: ActorRef,
    val config: Configuration,
    val executionContext: EncryptionExecutionContext
) extends EncryptionManagerService
    with ActorBackend
    with Logging
    with FutureInitialisingComponent {

  initialise()

  private def secretBase64: String = config.get[String]("sodium.secret")
  private def secret: Array[Byte] = decode(secretBase64)

  private def decode(base64: String): Array[Byte] =
    java.util.Base64.getDecoder.decode(base64)

  override def initialiseFuture(): Future[Unit] = {
    for {
      _: Int <- sendAndReceive[Init, Int] {
        Init(secret)
      }
    } yield ()
  }

  def encrypt(plainText: Array[Byte]): Future[Encrypted] =
    sendAndReceive[Encrypt, Encrypted] {
      Encrypt(plainText)
    }

  def decrypt(encrypted: Encrypted): Future[Decrypted] =
    sendAndReceive[Encrypted, Decrypted] {
      encrypted
    }

  def generateSecret(n: Int): Future[Secret] =
    sendAndReceive[GenerateSecret, Secret] {
      GenerateSecret(n)
    }

}
