import actors.TxFilterAuthActor.TxInputOutput
import akka.actor.{Actor, ActorRef, PoisonPill}
import akka.pattern.pipe
import com.github.nscala_time.time.Imports.DateTime
import dao.{DuplicateHookException, HookWithThreshold, HookDao}
import org.apache.commons.logging.LogFactory
import org.bitcoinj.core._
import org.bitcoinj.script.ScriptException
import org.slf4j.LoggerFactory
import play.api.libs.concurrent.InjectedActorSupport
import play.api.libs.json.{JsObject, Json, Writes}
import services.MemPoolWatcherService
import slick.DatabaseExecutionContext

import scala.collection.immutable.ArraySeq
import scala.concurrent.ExecutionContext
import scala.jdk.CollectionConverters.CollectionHasAsScala
import scala.util.{Failure, Success}

//noinspection TypeAnnotation
package object actors {

  trait HookActorFactory[X] {
    def apply(x: X): Actor
  }

  case class CreateActors[X, Y](uri: X, hook: Y)
  case class HookNotRegisteredException[X](uri: X) extends Exception(s"No webhook registered for $uri")
  case class HookNotStartedException[X](uri: X) extends Exception(s"No webhook started for $uri")
  case class HookAlreadyRegisteredException[Y](hook: Y) extends Exception(s"Webhook already registered with same key as $hook")
  case class HookAlreadyStartedException[X](uri: X) extends Exception(s"Webhook already started for $uri")

  case class Register[X](hook: X)
  case class Unregister[X](hook: X)
  case class Started[X](hook: X)
  case class Stopped[X](hook: X)
  case class Registered[X](hook: X)
  case class Start[X](uri: X)
  case class Stop[X](uri: X)

  trait TxUpdateActor extends Actor {

    val memPoolWatcher: MemPoolWatcherService

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

  trait HooksManagerActor[X, Y] extends Actor with InjectedActorSupport {

    private val logger = LogFactory.getLog(classOf[HooksManagerActor[X, Y]])

    val dao: HookDao[X, Y]
    val messagingActorFactory: HookActorFactory[X]
    val filteringActorFactory: TxFilterNoAuthActor.Factory
    val databaseExecutionContext: DatabaseExecutionContext

    var actors: Map[X, Array[ActorRef]] = Map()

    implicit val ec: ExecutionContext = databaseExecutionContext

    def hookTypePrefix: String
    def encodeKey(key: X): String

    implicit class HookFor(key: X) {
      def withHook[R](fn: Y => R): Unit = {
        dao.find(key) map {
          case Some(hook) => Success(fn(hook))
          case None => Failure(HookNotRegisteredException(key))
        } pipeTo sender
      }
    }

    def fail(ex: Exception): Unit = {
      sender ! Failure(ex)
    }

    def provided(condition: => Boolean, block: => Unit, ex: => Exception): Unit = {
      if (condition) block else fail(ex)
    }

    override def receive: Receive = {

      case Register(hook: Y) =>
        dao.insert(hook) map {
          _ => Success(Registered(hook))
        } recover {
          case DuplicateHookException(_) => Failure(HookAlreadyRegisteredException(hook))
        } pipeTo sender

      case Start(uri: X) =>
        logger.debug(s"Received start request for $uri")
        provided(!(actors contains uri), uri withHook (hook => {
          self ! CreateActors(uri, hook)
          Started(hook)
        }), HookAlreadyStartedException(uri))

      case Stop(uri: X) =>
        provided (actors contains uri, {
          actors(uri).foreach(_ ! PoisonPill)
          actors -= uri
          uri withHook (hook => Stopped(hook))
        }, HookNotStartedException(uri))

      case CreateActors(key: X, hook: HookWithThreshold) =>
        val actorId = encodeKey(key)
        val messagingActor =
          injectedChild(messagingActorFactory(key), name = s"$hookTypePrefix-messenger-$actorId")
        val filteringActor =
          injectedChild(filteringActorFactory(messagingActor, _.value >= hook.threshold),
            name = s"$hookTypePrefix-filter-$actorId")
        actors += key -> Array(messagingActor, filteringActor)

    }

  }
}
