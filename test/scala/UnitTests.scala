import actors.TxFilterAuthActor.Auth
import actors.WebhookManagerActor.{Webhook, WebhookNotRegisteredException}
import actors.{HttpBackendSelection, TxFilterAuthActor, TxWebhookMessagingActor, TxUpdate, WebhookManagerActor}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.github.nscala_time.time.Imports.DateTime
import com.google.common.util.concurrent.ListenableFuture
import com.google.inject.AbstractModule
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core._
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener
import org.bitcoinj.params.MainNetParams
import org.scalamock.matchers.ArgCapture.CaptureAll
import org.scalamock.scalatest.MockFactory
import org.scalamock.util.Defaultable
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.inject
import play.api.inject.guice.GuiceInjectorBuilder
import play.api.libs.json.{JsArray, Json}
import play.libs.akka.AkkaGuiceSupport
import services._

import java.net.URI
import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.duration.DurationInt
import scala.io.Source

//noinspection TypeAnnotation
class UnitTests extends TestKit(ActorSystem("meso-alert-test"))
  with Matchers
  with AnyWordSpecLike
  with MockFactory
  with ScalaFutures
  with BeforeAndAfterAll
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

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  class TestModule extends AbstractModule with AkkaGuiceSupport {

    override def configure(): Unit = {
//      bindActor(classOf[WebhooksActor], "webhooks-actor")
      bindActorFactory(classOf[TxWebhookMessagingActor], classOf[TxWebhookMessagingActor.Factory])
    }
  }

  def fixture = new {

    implicit val timeout = Timeout(1.second)

    lazy val mockMemPoolWatcher = mock[MemPoolWatcherService]
    lazy val mockWs = mock[WebSocketMock]
    lazy val mockUser = mock[User]
    lazy val mockUserManager = mock[UserManagerService]

    val params = MainNetParams.get()
    class MockPeerGroup extends PeerGroup(params)
    val mockPeerGroup = mock[MockPeerGroup]

    lazy val transactions = Json.parse(Source.fromResource("tx_valid.json").getLines.mkString)
      .as[Array[JsArray]].map(_.value).filter(_.size > 1)
      .map(testData => params.getDefaultSerializer.makeTransaction(HEX.decode(testData(1).as[String].toLowerCase)))

    val injector = new GuiceInjectorBuilder()
      .bindings(new TestModule)
      .overrides(inject.bind(classOf[ActorSystem]).toInstance(system))
      .overrides(inject.bind(classOf[MemPoolWatcherService]).toInstance(mockMemPoolWatcher))
      .build()

    lazy val mockWsActor = system.actorOf(MockWebsocketActor.props(mockWs))

    lazy val txWatchActor =
      system.actorOf(TxFilterAuthActor.props(mockWsActor, mockMemPoolWatcher, mockUserManager))

    lazy val webhooksActor = {
      system.actorOf(
        WebhookManagerActor.props(mockMemPoolWatcher,
          injector.instanceOf[HttpBackendSelection],
          injector.instanceOf[TxWebhookMessagingActor.Factory])
      )
    }
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

      val memPoolWatcher = new MemPoolWatcher(new PeerGroupSelection() {
        val params = f.params
        val peerGroup = f.mockPeerGroup
      })
      memPoolWatcher.addListener(f.txWatchActor)

      val txWatchActor = system.actorOf(TxFilterAuthActor.props(f.mockWsActor, memPoolWatcher, f.mockUserManager))

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
      //noinspection SpellCheckingInspection
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

    "WebhooksActor" should {

      // akka timeout
      implicit val timeout = Timeout(1.second)

      "return WebhookNotRegistered when trying to start an unregistered hook" in {
        val f = fixture
       val future = f.webhooksActor ? WebhookManagerActor.Start(uri = new URI("http://test"))
        whenReady(future) {
          case _: WebhookNotRegisteredException =>
            succeed
          case x =>
            fail(s"Received $x instead of WebhookNotRegisteredException")
        }
      }

      "return Registered when registering a new hook" in {
        val f = fixture
        val hook = Webhook(uri = new URI("http://test"), threshold = 100L)
        val future = f.webhooksActor ? WebhookManagerActor.Register(hook)
        whenReady(future) {
          case WebhookManagerActor.Registered(x) =>
            x shouldBe hook
          case x =>
            fail(s"Received $x instead of Registered")
        }
      }

      "correctly register, start and stop a web hook" in {
        val f = fixture
        (f.mockMemPoolWatcher.addListener _).expects(*).once()
        val uri = new URI("http://test")
        val hook = Webhook(uri, threshold = 100L)
        val future = for {
          registered <- f.webhooksActor ? WebhookManagerActor.Register(hook)
          started <- f.webhooksActor ? WebhookManagerActor.Start(uri)
          stopped <- f.webhooksActor ? WebhookManagerActor.Stop(uri)
        } yield (registered, started, stopped)
        whenReady(future) {
          case (WebhookManagerActor.Registered(x), WebhookManagerActor.Started(y), WebhookManagerActor.Stopped(z)) =>
            x shouldBe hook
            y shouldBe hook
            z shouldBe hook
          case x =>
            fail(s"Received $x")
        }
      }
    }

  }

}
