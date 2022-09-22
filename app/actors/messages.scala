package actors

import dao.Hook
import org.bitcoinj.core.TransactionConfidence.ConfidenceType
import org.bitcoinj.core._
import org.bitcoinj.script.ScriptException
import play.api.libs.json.{JsObject, Json, Writes}
import slick.lifted.MappedTo

import scala.jdk.CollectionConverters.CollectionHasAsScala

case class Register[X](hook: Hook[X])
case class Update[X](hook: Hook[X])
case class Unregister[X](hook: Hook[X])
case class Started[X](hook: X)
case class Stopped[X](hook: X)
case class Registered[X](hook: Hook[X])
case class Updated[X](hook: Hook[X])
case class Start[X](key: X)
case class Stop[X](key: X)

case class TxInputOutput(address: Option[String], value: Option[Long])

case class TxHash(value: String) extends AnyVal with MappedTo[String]

object TxHash {
  def apply(tx: Transaction): TxHash = TxHash(tx.getTxId.toString)
}

case class TxConfidence(
    confType: ConfidenceType,
    depthInBlocks: Int
)

case class TxUpdate(
    hash: TxHash,
    value: Long,
    time: java.time.LocalDateTime,
    isPending: Boolean,
    outputs: Seq[TxInputOutput],
    inputs: Seq[TxInputOutput],
    confidence: Option[TxConfidence]
)

object TxUpdate {

  def apply(tx: Transaction)(implicit params: NetworkParameters): TxUpdate =
    TxUpdate(
      hash = TxHash(tx),
      value = tx.getOutputSum.value,
      time = java.time.LocalDateTime.now(),
      isPending = tx.isPending,
      inputs = (for (input <- tx.getInputs.asScala)
        yield TxInputOutput(address(input), value(input))).toSeq,
      outputs = (for (output <- tx.getOutputs.asScala)
        yield TxInputOutput(address(output), value(output))).toSeq,
      confidence = {
        tx.getConfidence() match {
          case null => None
          case conf =>
            Some(TxConfidence(conf.getConfidenceType, conf.getDepthInBlocks))
        }
      }
    )

  // https://bitcoin.stackexchange.com/questions/83481/bitcoinj-java-library-not-decoding-input-addresses-for-some-transactions
  def address(
      input: TransactionInput
  )(implicit params: NetworkParameters): Option[String] = {
    val chunks = input.getScriptSig.getChunks.asScala
    if (chunks.isEmpty) {
      Some(
        LegacyAddress
          .fromScriptHash(params, Utils.sha256hash160(input.getScriptBytes))
          .toString
      )
    } else {
      val pubKey = chunks.takeRight(1).head
      val hash = Utils.sha256hash160(pubKey.data)
      if (chunks.size == 2)
        Some(LegacyAddress.fromPubKeyHash(params, hash).toString)
      else
        Some(SegwitAddress.fromHash(params, hash).toString)
    }
  }

  def address(
      output: TransactionOutput
  )(implicit params: NetworkParameters): Option[String] = {
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

  // noinspection ConvertExpressionToSAM
  implicit val txUpdateWrites: Writes[TxUpdate] = new Writes[TxUpdate] {
    def writes(tx: TxUpdate): JsObject = Json.obj(
      fields = "hash" -> tx.hash.value,
      "value" -> tx.value,
      "time" -> tx.time.toString(),
      "isPending" -> tx.isPending,
      "outputs" -> Json.arr(tx.outputs),
      "inputs" -> Json.arr(tx.inputs)
    )
  }
}
