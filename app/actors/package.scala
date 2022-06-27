import actors.AuthenticationActor.TxInputOutput
import akka.actor.{Actor, ActorRef}
import com.github.nscala_time.time.Imports.DateTime
import dao.Hook
import org.bitcoinj.core._
import org.bitcoinj.script.ScriptException
import org.slf4j.Logger
import play.api.libs.json.{JsObject, Json, Writes}
import services.MemPoolWatcherService

import scala.collection.immutable.ArraySeq
import scala.jdk.CollectionConverters.CollectionHasAsScala

//noinspection TypeAnnotation
package object actors {

  trait HookActorFactory[X] {
    def apply(x: X): Actor
  }

  case class CreateActors[X](uri: X, hook: Hook[X])
  case class HookNotRegisteredException[X](uri: X) extends Exception(s"No webhook registered for $uri")
  case class HookNotStartedException[X](uri: X) extends Exception(s"No webhook started for $uri")
  case class HookAlreadyRegisteredException[X](hook: Hook[X]) extends Exception(s"Webhook already registered with same key as $hook")
  case class HookAlreadyStartedException[X](uri: X) extends Exception(s"Webhook already started for $uri")

  case class Register[X](hook: Hook[X])
  case class Update[X](hook: Hook[X])
  case class Unregister[X](hook: Hook[X])
  case class Started[X](hook: X)
  case class Stopped[X](hook: X)
  case class Registered[X](hook: Hook[X])
  case class Updated[X](hook: Hook[X])
  case class Start[X](key: X)
  case class Stop[X](key: X)

  trait TxUpdateActor {
    val self: ActorRef
    val memPoolWatcher: MemPoolWatcherService
    val logger: Logger

    def registerWithWatcher(): Unit = {
      logger.info("Registering new mem pool listener... ")
      memPoolWatcher.addListener(self)
      logger.info("registration complete.")
    }
  }

  implicit val txInputOutputWrites = new Writes[TxInputOutput] {
    def writes(inpOut: TxInputOutput): JsObject = {
      val addressField: Array[(String, Json.JsValueWrapper)] =
        Array(inpOut.address).filterNot(_.isEmpty).map("address" -> _.get)
      val valueField: Array[(String, Json.JsValueWrapper)] =
        Array(inpOut.value).filterNot(_.isEmpty).map("value" -> _.get)
      Json.obj(ArraySeq.unsafeWrapArray(addressField ++ valueField): _*)
    }
  }

  val blockChairBaseURL = "https://www.blockchair.com/bitcoin"
  def linkToTxHash(hash: String) = s"<$blockChairBaseURL/transaction/$hash|$hash>"
  def linkToAddress(address: String) = s"<$blockChairBaseURL/address/$address|$address>"

  def formatSatoshi(value: Long): String = (value / 100000000L).toString

  def formatOutputAddresses(outputs: Seq[TxInputOutput]): String =
    outputs.filterNot(_.address.isEmpty)
      .map(output => output.address.get)
      .distinct
      .map(output => linkToAddress(output))
      .mkString(", ")

  def message(tx: TxUpdate): String = {
    s"New transaction ${linkToTxHash(tx.hash)} with value ${formatSatoshi(tx.value)} BTC to " +
      s"addresses ${formatOutputAddresses(tx.outputs)}"
  }

  case class TxUpdate(hash: String, value: Long, time: DateTime, isPending: Boolean,
                       outputs: Seq[TxInputOutput], inputs: Seq[TxInputOutput])

  object TxUpdate {

    def apply(tx: Transaction)(implicit params: NetworkParameters): TxUpdate =
      TxUpdate(
        hash = tx.getTxId.toString,
        value = tx.getOutputSum.value,
        time = DateTime.now(),
        isPending = tx.isPending,
        inputs =
          (for (input <- tx.getInputs.asScala)
            yield AuthenticationActor.TxInputOutput(address(input), value(input))).toSeq,
        outputs =
          (for (output <- tx.getOutputs.asScala)
            yield AuthenticationActor.TxInputOutput(address(output), value(output))).toSeq,
      )

    // https://bitcoin.stackexchange.com/questions/83481/bitcoinj-java-library-not-decoding-input-addresses-for-some-transactions
    def address(input: TransactionInput)(implicit params: NetworkParameters): Option[String] = {
      val chunks = input.getScriptSig.getChunks.asScala
      if (chunks.isEmpty) {
        Some(LegacyAddress.fromScriptHash(params, Utils.sha256hash160(input.getScriptBytes)).toString)
      } else {
        val pubKey = chunks.takeRight(1).head
        val hash = Utils.sha256hash160(pubKey.data)
        if (chunks.size == 2)
          Some(LegacyAddress.fromPubKeyHash(params, hash).toString)
        else
          Some(SegwitAddress.fromHash(params, hash).toString)
      }
    }

    def address(output: TransactionOutput)(implicit params: NetworkParameters): Option[String] = {
      try {
        Some(output.getScriptPubKey.getToAddress(params).toString)
      } catch {
        case _: ScriptException =>
          None
      }
    }

    def value(input: TransactionInput): Option[Long] =
      if (input.getValue == null) None else Some(input.getValue.value)

    def value(output: TransactionOutput): Option[Long] =
      if (output.getValue == null) None else Some(output.getValue.value)

    implicit val txUpdateWrites = new Writes[TxUpdate] {
      def writes(tx: TxUpdate): JsObject = Json.obj(fields =
        "hash" -> tx.hash,
        "value" -> tx.value,
        "time" -> tx.time.toString(),
        "isPending" -> tx.isPending,
        "outputs" -> Json.arr(tx.outputs),
        "inputs" -> Json.arr(tx.inputs),
      )
    }
  }

}
