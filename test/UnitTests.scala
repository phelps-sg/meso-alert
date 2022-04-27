import actors.TxWatchActor
import actors.TxWatchActor.{Auth, TxUpdate}
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.github.nscala_time.time.Imports.DateTime
import com.google.common.util.concurrent.ListenableFuture
import org.bitcoinj.core.{Address, Coin, Transaction}
import org.bitcoinj.params.MainNetParams
import org.scalamock.matchers.ArgCapture.{CaptureAll, CaptureOne}
//import com.google.common.util.concurrent.ListenableFuture
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener
import org.scalamock.scalatest.MockFactory
import org.scalamock.util.Defaultable
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
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
  }

  "MemPoolWatcher" should {

    "send the correct TxUpdate message when a transaction update is received from " +
      "the bitcoinj peer group" in {

      val f = fixture

      // Configure user to not filter events.
      (f.mockUser.filter _).expects(*).returning(true).once()
      // The user will authenticate  successfully with id "test".
      (f.mockUserManager.authenticate _).expects("test").returning(f.mockUser)

      // Capture the update message sent to the web socket for later verification.
      val updateCapture = CaptureOne[TxUpdate]()
      (f.mockWs.update _).expects(capture(updateCapture)).once()

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

      // Configure a test bitcoinj transaction.
      val transaction: Transaction = new Transaction(f.params)

      // The first transaction output has a value of 100 Satoshi.

      //noinspection SpellCheckingInspection
      val outputAddress1 = "1A5PFH8NdhLy1raKXKxFoqUgMAPUaqivqp"
      val value1 = 100L
      transaction.addOutput(Coin.valueOf(value1), Address.fromString(f.params, outputAddress1))

      // The second transaction output has a value of 200 Satoshi.

      //noinspection SpellCheckingInspection
      val outputAddress2 = "1G47mSr3oANXMafVrR8UC4pzV7FEAzo3r9"
      val value2 = 200L
      transaction.addOutput(Coin.valueOf(value2), Address.fromString(f.params, outputAddress2))

      // Simulate a broadcast of the transaction from PeerGroup.
      listener.onTransaction(null, transaction)
      // We have to wait for the actors to process their messages.
      expectNoMessage()

      val receivedTx = updateCapture.value
      //noinspection ZeroIndexToHead
      receivedTx.outputAddresses(0) shouldBe outputAddress1
      receivedTx.outputAddresses(1) shouldBe outputAddress2
      receivedTx.value shouldBe value1 + value2
    }
  }

  "TxWatchActor" should {

    "provide updates when user is authenticated" in {

      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true, List())

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

      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true, List())

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

    val tx1 = TxUpdate("testHash1", 10, DateTime.now(), isPending = true, List())
    val tx2 = TxUpdate("testHash2", 1, DateTime.now(), isPending = true, List())

    val f = fixture
    (f.mockUserManager.authenticate _).expects("test").returning(f.mockUser)
    (f.mockMemPoolWatcher.addListener _).expects(*)

    f.txWatchActor ! Auth("test", "test")
    expectNoMessage()

    (f.mockUser.filter _).expects(tx1).returning(true)
    (f.mockWs.update _).expects(tx1)
    f.txWatchActor ! tx1
    expectNoMessage()

    (f.mockUser.filter _).expects(tx2).returning(false)
    f.txWatchActor ! tx2
    expectNoMessage()
  }

}
