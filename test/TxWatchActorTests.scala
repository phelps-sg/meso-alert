import actors.TxWatchActor
import actors.TxWatchActor.{Auth, TxUpdate}
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.github.nscala_time.time.Imports.DateTime
import com.google.common.util.concurrent.ListenableFuture
import org.bitcoinj.core.{Address, Coin, Peer, Transaction, TransactionOutput}
import org.bitcoinj.params.{MainNetParams, TestNet3Params}
import org.scalamock.matchers.ArgCapture.{CaptureAll, CaptureOne}
//import com.google.common.util.concurrent.ListenableFuture
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener
import org.scalamock.scalatest.MockFactory
import org.scalamock.util.Defaultable
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import services.{InvalidCredentialsException, MemPoolWatcher, MemPoolWatcherService, PeerGroupSelection, User, UserManagerService}

import java.util.concurrent.Executor

//noinspection TypeAnnotation
class TxWatchActorTests extends TestKit(ActorSystem("MySpec"))
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
  }

  "MemPoolWatcher" should {

    "send the correct TxUpdate message  when a transaction update is received from " +
      "the bitcoinj peer group" in {

      val f = fixture
      val updateCapture = CaptureOne[TxUpdate]()
      (f.mockUser.filter _).expects(*).returning(true).once()
      (f.mockUserManager.authenticate _).expects("test").returning(f.mockUser)
      (f.mockWs.update _).expects(capture(updateCapture)).once()

      val params = MainNetParams.get()
      class MockPeerGroup extends PeerGroup(params)

      implicit val d = new Defaultable[ListenableFuture[_]] {
        override val default = null
      }
      val mockPeerGroup = mock[MockPeerGroup]
      val c1 = CaptureAll[OnTransactionBroadcastListener]()
      (mockPeerGroup.addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener)).expects(capture(c1)).atLeastOnce()
      val memPoolWatcher = new MemPoolWatcher(new PeerGroupSelection() { val peerGroup = mockPeerGroup })
      memPoolWatcher.addListener(f.txWatchActor)

      val txWatchActor = system.actorOf(TxWatchActor.props(f.wsActor, memPoolWatcher, f.mockUserManager))
      txWatchActor ! Auth("test", "test")
      expectNoMessage()

      val listener = c1.value
      val transaction: Transaction = new Transaction(params)

      val outputAddress1 = "1A5PFH8NdhLy1raKXKxFoqUgMAPUaqivqp"
      val value1 = 100L
      transaction.addOutput(Coin.valueOf(value1), Address.fromString(params, outputAddress1))

      val outputAddress2 = "1G47mSr3oANXMafVrR8UC4pzV7FEAzo3r9"
      val value2 = 200L
      transaction.addOutput(Coin.valueOf(value2), Address.fromString(params, outputAddress2))

      listener.onTransaction(null, transaction)
      expectNoMessage()

      val receivedTx = updateCapture.value
      //noinspection ZeroIndexToHead
      receivedTx.outputAddresses(0) shouldBe outputAddress1
      receivedTx.outputAddresses(1) shouldBe outputAddress2
      receivedTx.value shouldBe value1 + value2
    }
  }

  "TxWatchActor" should {

    "provide updates when user is authentication" in {

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
