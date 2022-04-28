import actors.TxWatchActor
import actors.TxWatchActor.{Auth, TxUpdate}
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import play.api.libs.json.{JsArray, JsObject, JsPath, JsValue, Json, Reads, Writes}
import com.github.nscala_time.time.Imports.DateTime
import com.google.common.util.concurrent.ListenableFuture
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core.{Address, Coin, LegacyAddress, Sha256Hash, Transaction, TransactionInput, TransactionOutPoint, UnsafeByteArrayOutputStream, Utils}
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.script.ScriptOpCodes.OP_INVALIDOPCODE
import org.bitcoinj.script.{Script, ScriptOpCodes, ScriptPattern}
import org.scalamock.matchers.ArgCapture.{CaptureAll, CaptureOne}

import java.math.BigInteger
import java.nio.charset.StandardCharsets
import scala.collection.mutable
//import com.google.common.util.concurrent.ListenableFuture
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener
import org.scalamock.scalatest.MockFactory
import org.scalamock.util.Defaultable
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import scala.io.Source
import services._

//noinspection TypeAnnotation
class UnitTests extends TestKit(ActorSystem("MySpec"))
  with Matchers
  with AnyWordSpecLike
  with MockFactory
  with ImplicitSender {

  object MockWebsocketActor {
    def props(mock: WebSocketMock) = Props(new MockWebsocketActor(mock))
  }

  trait WebSocketMock {
    def update(tx: TxUpdate): Unit
  }

  class MockWebsocketActor(val mock: WebSocketMock) extends Actor {

    override def receive: Receive = {
      case tx: TxUpdate =>
        mock.update(tx)
      case _ =>
        fail("unrecognized message format")
    }
  }

  def fixture = new {
    val mockMemPoolWatcher = mock[MemPoolWatcherService]
    val mockWs = mock[WebSocketMock]
    val wsActor = system.actorOf(MockWebsocketActor.props(mockWs))
    val mockUser = mock[User]
    val mockUserManager = mock[UserManagerService]
    val txWatchActor = system.actorOf(TxWatchActor.props(wsActor, mockMemPoolWatcher, mockUserManager))
    val params = MainNetParams.get()
    class MockPeerGroup extends PeerGroup(params)
    val mockPeerGroup = mock[MockPeerGroup]
    val transactions = parseTransactions(Json.parse(Source.fromResource("tx_valid.json").getLines.mkString))

    private def parseScriptString(string: String) = {
      val words = string.split("[ \\t\\n]")
      val out = new UnsafeByteArrayOutputStream
      for (w <- words if w != "") {
        if (w.matches("^-?[0-9]*$")) { // Number
          val v = w.toLong
          if (v >= -1 && v <= 16) out.write(Script.encodeToOpN(v.toInt))
          else Script.writeBytes(out, Utils.reverseBytes(Utils.encodeMPI(BigInteger.valueOf(v), false)))
        }
        else if (w.matches("^0x[0-9a-fA-F]*$")) { // Raw hex data, inserted NOT pushed onto stack:
          out.write(HEX.decode(w.substring(2).toLowerCase))
        }
        else if (w.length >= 2 && w.startsWith("'") && w.endsWith("'")) { // Single-quoted string, pushed as data. NOTE: this is poor-man's
          // parsing, spaces/tabs/newlines in single-quoted strings won't work.
          Script.writeBytes(out, w.substring(1, w.length - 1).getBytes(StandardCharsets.UTF_8))
        }
        else if (ScriptOpCodes.getOpCode(w) != OP_INVALIDOPCODE) { // opcode, e.g. OP_ADD or OP_1:
          out.write(ScriptOpCodes.getOpCode(w))
        }
        else if (w.startsWith("OP_") && ScriptOpCodes.getOpCode(w.substring(3)) != OP_INVALIDOPCODE) out.write(ScriptOpCodes.getOpCode(w.substring(3)))
        else throw new RuntimeException("Invalid word: '" + w + "'")
      }
      new Script(out.toByteArray)
    }

    private def parseScriptPubKeys(inputs: JsValue): mutable.Map[TransactionOutPoint, Script] = {
      val scriptPubKeys = mutable.Map[TransactionOutPoint, Script]()
      inputs.as[JsArray].value.foreach { input =>
        val hash = input(0).as[String]
        val index = input(1).as[Int]
        val script = input(2).as[String]
        val sha256Hash = Sha256Hash.wrap(HEX.decode(hash))
        scriptPubKeys(new TransactionOutPoint(params, index, sha256Hash)) = parseScriptString(script)
      }
      scriptPubKeys
    }

    private def parseTransactions(testData: JsValue): Array[Transaction] = {
      testData.as[Array[JsArray]].map(_.value).filter(_.size > 1).map {
        testData => {
          val scriptPubKeys = parseScriptPubKeys(testData(0))
          params.getDefaultSerializer.makeTransaction(HEX.decode(testData(1).as[String].toLowerCase))
        }
      }
    }

  }

  "MemPoolWatcher" should {

    "send the correct TxUpdate message when a transaction update is received from " +
      "the bitcoinj peer group" in {

      val f = fixture

      // Configure user to not filter events.
      (f.mockUser.filter _).expects(*).returning(true).atLeastOnce()
      // The user will authenticate  successfully with id "test".
      (f.mockUserManager.authenticate _).expects("test").returning(f.mockUser)

      // Capture the update messages sent to the web socket for later verification.
      val updateCapture = CaptureAll[TxUpdate]()
      (f.mockWs.update _).expects(capture(updateCapture)).atLeastOnce()

      // This is required for raw types (see https://scalamock.org/user-guide/advanced_topics/).
      implicit val d = new Defaultable[ListenableFuture[_]] {
        override val default = null
      }

      // Capture the listeners.  The second listener will be the txWatchActor
      val listenerCapture = CaptureAll[OnTransactionBroadcastListener]()
      (f.mockPeerGroup.addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
        .expects(capture(listenerCapture)).atLeastOnce()

      val memPoolWatcher = new MemPoolWatcher(new PeerGroupSelection() { val peerGroup = f.mockPeerGroup })
      memPoolWatcher.addListener(f.txWatchActor)

      val txWatchActor = system.actorOf(TxWatchActor.props(f.wsActor, memPoolWatcher, f.mockUserManager))

      // Authenticate the user so that the actor is ready send updates.
      txWatchActor ! Auth("test", "test")
      expectNoMessage()

      val listener = listenerCapture.value

      def broadcastTransaction(tx: Transaction): Unit = {
        // Simulate a broadcast of the transaction from PeerGroup.
        listener.onTransaction(null, tx)
        // We have to wait for the actors to process their messages.
        expectNoMessage()
      }

      // Configure a test bitcoinj transaction.
      val transaction1 = f.transactions.head
      broadcastTransaction(transaction1)

      val receivedTx1 = updateCapture.value
      receivedTx1.outputs.head.address.get shouldBe "1AJbsFZ64EpEfS5UAjAfcUG8pH8Jn3rn1F"
      receivedTx1.value shouldBe 1000000

      val transaction2 = new Transaction(f.params)

      //noinspection SpellCheckingInspection
      val outputAddress1 = "1A5PFH8NdhLy1raKXKxFoqUgMAPUaqivqp"
      val value1 = 100L
      transaction2.addOutput(Coin.valueOf(value1), Address.fromString(f.params, outputAddress1))

      //noinspection SpellCheckingInspection
      val outputAddress2 = "1G47mSr3oANXMafVrR8UC4pzV7FEAzo3r9"
      val value2 = 200L
      transaction2.addOutput(Coin.valueOf(value2), Address.fromString(f.params, outputAddress2))

      broadcastTransaction(transaction2)

      val receivedTx2 = updateCapture.value
      //noinspection ZeroIndexToHead
      receivedTx2.outputs(0).address.get shouldBe outputAddress1
      receivedTx2.outputs(1).address.get shouldBe outputAddress2
      receivedTx2.value shouldBe value1 + value2
    }
  }

  "TxWatchActor" should {

    "provide updates when user is authenticated" in {

      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true, List(), List())

      val f = fixture
      (f.mockUser.filter _).expects(tx).returning(true)
      (f.mockUserManager.authenticate _).expects("test").returning(f.mockUser)
      (f.mockMemPoolWatcher.addListener _).expects(*)
      (f.mockWs.update _).expects(tx)

      f.txWatchActor ! Auth("test", "test")
      expectNoMessage()

      f.txWatchActor ! tx
      expectNoMessage()
    }

    "not provide updates when credentials are invalid" in {

      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true, List(), List())

      val f = fixture
      (f.mockUserManager.authenticate _).expects("test").throws(InvalidCredentialsException())

      val probe = TestProbe()
      probe.watch(f.txWatchActor)

      f.txWatchActor ! Auth("test", "test")
      expectNoMessage()

      probe.expectTerminated(f.txWatchActor)

      f.txWatchActor ! tx
      expectNoMessage()
    }
  }

  "only provide updates according to the user's filter" in {

    val tx1 = TxUpdate("testHash1", 10, DateTime.now(), isPending = true, List(), List())
    val tx2 = TxUpdate("testHash2", 1, DateTime.now(), isPending = true, List(), List())

    val f = fixture
    (f.mockUserManager.authenticate _).expects("test").returning(f.mockUser)
    (f.mockMemPoolWatcher.addListener _).expects(*)

    f.txWatchActor ! Auth("test", "test")
    expectNoMessage()

    (f.mockUser.filter _).expects(tx1).returning(true)
    (f.mockWs.update _).expects(tx1).once()
    f.txWatchActor ! tx1
    expectNoMessage()

    (f.mockUser.filter _).expects(tx2).returning(false)
    f.txWatchActor ! tx2
    expectNoMessage()
  }

}
