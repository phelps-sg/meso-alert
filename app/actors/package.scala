import actors.TxFilterAuthActor.TxInputOutput
import akka.actor.Actor
import com.github.nscala_time.time.Imports.DateTime
import org.bitcoinj.core._
import org.bitcoinj.script.ScriptException
import org.slf4j.LoggerFactory
import play.api.libs.json.{JsObject, Json, Writes}
import services.MemPoolWatcherService

import scala.collection.immutable.ArraySeq
import scala.jdk.CollectionConverters.CollectionHasAsScala

//noinspection TypeAnnotation
package object actors {

  abstract class AbstractTxUpdateActor(val memPoolWatcher: MemPoolWatcherService) extends Actor {

    private val logger = LoggerFactory.getLogger(classOf[TxUpdate])

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

  case class TxUpdate( hash: String,
                       value: Long,
                       time: DateTime,
                       isPending: Boolean,
                       outputs: Seq[TxInputOutput],
                       inputs: Seq[TxInputOutput],
                     )

  object TxUpdate {

    def apply(tx: Transaction)(implicit params: NetworkParameters): TxUpdate =
      TxUpdate(
        hash = tx.getTxId.toString,
        value = tx.getOutputSum.value,
        time = DateTime.now(),
        isPending = tx.isPending,
        inputs =
          (for (input <- tx.getInputs.asScala)
            yield TxFilterAuthActor.TxInputOutput(address(input), value(input))).toSeq,
        outputs =
          (for (output <- tx.getOutputs.asScala)
            yield TxFilterAuthActor.TxInputOutput(address(output), value(output))).toSeq,
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
