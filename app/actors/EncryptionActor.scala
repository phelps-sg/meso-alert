package actors

import actors.MessageHandlers.UnRecognizedMessageHandlerWithBounce
import akka.actor.{Actor, Props}
import dao.Secret
import org.abstractj.kalium.NaCl
import org.abstractj.kalium.crypto.{Random, SecretBox}
import play.api.Logging

import scala.util.{Failure, Success}

object EncryptionActor {

  final case class Init(secretKey: Array[Byte])

  sealed trait EncryptionCommand
  final case class GenerateSecret(numBytes: Int) extends EncryptionCommand
  final case class Encrypt(plainText: Array[Byte]) extends EncryptionCommand
  final case class Encrypted(nonce: Array[Byte], cipherText: Array[Byte])
      extends EncryptionCommand

  final case class Decrypted(plainText: Array[Byte]) {
    def asString: String = plainText.map(_.toChar).mkString
  }
  final case class SodiumInitialisationError(message: String)
      extends Exception(message)

  def props(): Props = Props(new EncryptionActor())

}

class EncryptionActor
    extends Actor
    with Logging
    with UnRecognizedMessageHandlerWithBounce {

  import actors.EncryptionActor._

  override def receive: Receive = {

    case Init(secretKey: Array[Byte]) =>
      val result = NaCl.init()
      if (result < 0) {
        sender() ! Failure(
          SodiumInitialisationError(s"sodium_init() returned $result")
        )
      } else if (result > 0) {
        logger.warn("Sodium library already initialised")
        sender() ! Success(result)
      } else {
        sender() ! Success(result)
      }
      context.become(
        initialised(box = new SecretBox(secretKey), rng = new Random())
      )

    case message =>
      unrecognizedMessage(message)
  }

  def initialised(box: SecretBox, rng: Random): Receive = {
    case cmd: EncryptionCommand =>
      cmd match {

        case Encrypt(plainText: Array[Byte]) =>
          val nonce = rng.randomBytes(
            NaCl.Sodium.CRYPTO_SECRETBOX_XSALSA20POLY1305_NONCEBYTES
          )
          val cipherText = box.encrypt(nonce, plainText)
          sender() ! Success(Encrypted(nonce, cipherText))

        case Encrypted(nonce, cipherText) =>
          sender() ! Success(Decrypted(box.decrypt(nonce, cipherText)))

        case GenerateSecret(n) =>
          sender() ! Success(Secret(rng.randomBytes(n)))

      }

    case message =>
      unrecognizedMessage(message)

  }

}
