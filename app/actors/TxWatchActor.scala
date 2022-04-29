package actors

import akka.actor.{Actor, ActorRef, PoisonPill, Props}
import akka.http.scaladsl.model.ws.TextMessage
import com.github.nscala_time.time.Imports.DateTime
import org.bitcoinj.core._
import org.bitcoinj.script.ScriptException
import org.slf4j.{Logger, LoggerFactory}
import play.api.libs.functional.syntax.toFunctionalBuilderOps
import play.api.libs.json._
import services.{InvalidCredentialsException, MemPoolWatcherService, UserManagerService}

import scala.collection.immutable.ArraySeq
import scala.jdk.CollectionConverters.CollectionHasAsScala

//noinspection TypeAnnotation
object TxWatchActor {

  def props(out: ActorRef, memPoolWatcher: MemPoolWatcherService, userManager: UserManagerService): Props =
    Props(new TxWatchActor(out, memPoolWatcher, userManager))

  case class TxInputOutput(address: Option[String], value: Option[Long])

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
          yield TxWatchActor.TxInputOutput(address(input), value(input))).toSeq,
      outputs =
        (for (output <- tx.getOutputs.asScala)
          yield TxWatchActor.TxInputOutput(address(output), value(output))).toSeq,
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
  }
  case class Auth(id: String, token: String) {
    def message: TextMessage.Strict = TextMessage(authWrites.writes(this).toString())
  }
  case class Die(message: String)

  implicit val authWrites = new Writes[Auth] {
    def writes(auth: Auth): JsObject = Json.obj(
      "id" -> auth.id,
      "token" -> auth.token
    )
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

  implicit val authReads: Reads[Auth] =
    ((JsPath \ "id").read[String] and (JsPath \ "token").read[String])(Auth.apply _)

}

//noinspection TypeAnnotation
class TxWatchActor(out: ActorRef, memPoolWatcher: MemPoolWatcherService, userManager: UserManagerService) extends Actor {

  private val logger: Logger = LoggerFactory.getLogger(classOf[TxWatchActor])

  import TxWatchActor._

  def registerWithWatcher(): Unit = {
    logger.info("Registering new mem pool listener... ")
    memPoolWatcher.addListener(self)
    logger.info("registration complete.")
  }

  override def receive: Receive = unauthorized

  private def deathHandler: Receive = {
    case Die(reason) =>
      logger.info(s"Died due to reason: $reason")
      self ! PoisonPill
  }

  def unauthorized: Receive = deathHandler.orElse {
    case auth: Auth =>
      logger.info(s"Received auth request for id ${auth.id}")
      authenticate(auth)
    case x =>
      logger.warn(s"Unrecognized message $x")
  }

  def authenticate(auth: Auth): Unit = {
    try {
      val user = userManager.authenticate(auth.id)

      def authorized: Receive = deathHandler.orElse {
        case txUpdate: TxUpdate =>
          if (user.filter(txUpdate)) out ! txUpdate
      }

      context.become(authorized)
      registerWithWatcher()
    } catch {
      case _: InvalidCredentialsException =>
        self ! Die(s"Authentication failed for ${auth.id}.")
    }
  }

}
