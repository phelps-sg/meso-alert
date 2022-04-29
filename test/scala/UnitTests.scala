import actors.TxWatchActor
import actors.TxWatchActor.{Auth, TxUpdate}
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.github.nscala_time.time.Imports.DateTime
import com.google.common.util.concurrent.ListenableFuture
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core._
import org.bitcoinj.params.MainNetParams
import org.scalamock.matchers.ArgCapture.CaptureAll
import play.api.libs.json.{JsArray, Json}
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener
import org.scalamock.scalatest.MockFactory
import org.scalamock.util.Defaultable
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import services._

import scala.io.Source

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
    val mockWsActor = system.actorOf(MockWebsocketActor.props(mockWs))
    val mockUser = mock[User]
    val mockUserManager = mock[UserManagerService]
    val txWatchActor = system.actorOf(TxWatchActor.props(mockWsActor, mockMemPoolWatcher, mockUserManager))
    val params = MainNetParams.get()
    class MockPeerGroup extends PeerGroup(params)
    val mockPeerGroup = mock[MockPeerGroup]
    val transactions = Json.parse(Source.fromResource("tx_valid.json").getLines.mkString)
        .as[Array[JsArray]].map(_.value).filter(_.size > 1)
        .map(testData => params.getDefaultSerializer.makeTransaction(HEX.decode(testData(1).as[String].toLowerCase)))
  }

  //noinspection ZeroIndexToHead
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

      val txWatchActor = system.actorOf(TxWatchActor.props(f.mockWsActor, memPoolWatcher, f.mockUserManager))

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
      receivedTx1.outputs.size shouldBe 1
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
      receivedTx2.outputs.size shouldBe 2
      receivedTx2.inputs.isEmpty shouldBe true
      receivedTx2.outputs(0).address.get shouldBe outputAddress1
      receivedTx2.outputs(1).address.get shouldBe outputAddress2
      receivedTx2.value shouldBe value1 + value2

      // https://www.blockchain.com/btc/tx/6359f0868171b1d194cbee1af2f16ea598ae8fad666d9b012c8ed2b79a236ec4
      val transaction3 = f.transactions(1)
      broadcastTransaction(transaction3)

      val receivedTx3 = updateCapture.value
      receivedTx3.inputs.size shouldBe 1
      receivedTx3.outputs.size shouldBe 2
      receivedTx3.value shouldBe 300000000
      //noinspection SpellCheckingInspection
      receivedTx3.inputs(0).address.get shouldBe "15vScfMHNrXN4QvWe54q5hwfVoYwG79CS1"
      //noinspection SpellCheckingInspection
      receivedTx3.outputs(0).address.get shouldBe "1H8ANdafjpqYntniT3Ddxh4xPBMCSz33pj"
      //noinspection SpellCheckingInspection
      receivedTx3.outputs(1).address.get shouldBe "1Am9UTGfdnxabvcywYG2hvzr6qK8T3oUZT"

      // https://www.blockchain.com/btc/tx/73965c0ab96fa518f47df4f3e7201e0a36f163c4857fc28150d277caa8589259
      val transaction4 = f.transactions(2)
      broadcastTransaction(transaction4)

      val receivedTx4 = updateCapture.value
      receivedTx4.value shouldBe 923985
      receivedTx4.inputs.size shouldBe 1
      receivedTx4.outputs.size shouldBe 2
      // See https://gitlab.com/mesonomics/meso-alert/-/issues/24
//      receivedTx4.inputs(0).address.get shouldBe "bc1qwqdg6squsna38e46795at95yu9atm8azzmyvckulcc7kytlcckxswvvzej"
      //noinspection SpellCheckingInspection
      receivedTx4.outputs(0).address.get shouldBe "1AyQnFZk9MbjLFXSWJ7euNbGhaNpjPvrSq"
      //noinspection SpellCheckingInspection
      receivedTx4.outputs(1).address.get shouldBe "bc1qwqdg6squsna38e46795at95yu9atm8azzmyvckulcc7kytlcckxswvvzej"
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
