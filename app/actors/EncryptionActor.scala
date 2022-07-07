package actors

import akka.actor.{Actor, Props}
import org.abstractj.kalium.NaCl
import org.abstractj.kalium.crypto.{Random, SecretBox}
import play.api.Logging

import scala.util.{Failure, Success}

object EncryptionActor {

  case class Init(secretKey: Array[Byte])
  case class Encrypt(plainText: Array[Byte])
  case class Encrypted(cipherText: Array[Byte], nonce: Array[Byte])
  case class Decrypted(plainText: Array[Byte]) {
    def asString: String = plainText.map(_.toChar).mkString
  }
  case class SodiumInitialisationError(message: String) extends Exception(message)

  def props(): Props = Props(new EncryptionActor())

}

class EncryptionActor extends Actor with Logging {

  import actors.EncryptionActor._

  override def receive: Receive = {

    case Init(secretKey: Array[Byte]) =>
      val result = NaCl.init()
      if (result < 0) {
        sender() ! Failure(SodiumInitialisationError(s"sodium_init() returned $result"))
      } else if (result > 0) {
        sender() ! Failure(SodiumInitialisationError("sodium library already initialised"))
      } else {
        sender() ! Success
      }
      val rng = new Random()
      val box = new SecretBox(secretKey)
      context.become(initialised(box, rng))
  }

  def initialised(box: SecretBox, rng: Random): Receive = {

    case Encrypt(plainText: Array[Byte]) =>
      val nonce = rng.randomBytes(NaCl.Sodium.CRYPTO_SECRETBOX_XSALSA20POLY1305_NONCEBYTES)
      val cipherText = box.encrypt(nonce, plainText)
      sender() ! Success(Encrypted(nonce, cipherText))

    case Encrypted(nonce, cipherText) =>
      sender() ! Success(Decrypted(box.decrypt(nonce, cipherText)))

    case message =>
      sender() ! Failure(new RuntimeException(s"Unrecognized message: $message"))

  }

}
