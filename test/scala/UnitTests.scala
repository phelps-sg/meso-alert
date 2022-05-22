import actors.TxFilterAuthActor.{Auth, TxInputOutput}
import actors.WebhooksManagerActor.{Registered, Started, Stopped, WebhookNotRegisteredException}
import actors.{HttpBackendSelection, TxFilterAuthActor, TxFilterNoAuthActor, TxUpdate, TxWebhookMessagingActor, WebhooksManagerActor}
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.github.nscala_time.time.Imports.DateTime
import com.google.common.util.concurrent.ListenableFuture
import com.google.inject.AbstractModule
import dao.{Webhook, WebhookDao}
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
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.inject
import play.api.inject.guice.GuiceInjectorBuilder
import play.api.libs.json.{JsArray, Json}
import play.libs.akka.AkkaGuiceSupport
import services._
import slick.BtcPostgresProfile.api._
import slick.dbio.DBIO
import slick.jdbc.JdbcBackend
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables, jdbc}

import java.net.URI
import javax.inject.Provider
import scala.collection.mutable
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.io.Source

//noinspection TypeAnnotation
class UnitTests extends TestKit(ActorSystem("meso-alert-test"))
  with AnyWordSpecLike
  with PostgresContainer
  with Matchers
  with MockFactory
  with ScalaFutures
  with BeforeAndAfterAll
  with ImplicitSender {

  // akka timeout
  implicit val akkaTimeout = Timeout(5.seconds)

  // whenReady timeout
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(5, Millis))

  trait WebSocketMock {
    def update(tx: TxUpdate): Unit
  }

  object MockWebsocketActor {
    def props(mock: WebSocketMock) = Props(new MockWebsocketActor(mock))
  }

  class MockWebsocketActor(val mock: WebSocketMock) extends Actor {

    override def receive: Receive = {
      case tx: TxUpdate =>
        mock.update(tx)
      case _ =>
        fail("unrecognized message format")
    }
  }

  trait WebhookManagerMock {
    def start(uri: URI): Unit
    def register(hook: Webhook): Unit
    def stop(uri: URI): Unit
  }

  object MockWebhookManagerActor {
    def props(mock: WebhookManagerMock) = Props(new MockWebhookManagerActor(mock))
  }

  class MockWebhookManagerActor(val mock: WebhookManagerMock) extends Actor {
    val hooks = mutable.Map[URI, Webhook]()
    override def receive: Receive = {
      case WebhooksManagerActor.Start(uri) =>
        mock.start(uri)
        sender ! Started(hooks(uri))
      case WebhooksManagerActor.Register(hook) =>
        mock.register(hook)
        hooks(hook.uri) = hook
        sender ! Registered(hook)
      case WebhooksManagerActor.Stop(uri) =>
        mock.stop(uri)
        sender ! Stopped(hooks(uri))
      case x =>
        fail(s"unrecognized message: $x")
    }
  }

  lazy val db: JdbcBackend.Database = database.asInstanceOf[JdbcBackend.Database]

  class TestModule extends AbstractModule with AkkaGuiceSupport {

    override def configure(): Unit = {
      bind(classOf[Database]).toProvider(new Provider[Database] {
        val get: jdbc.JdbcBackend.Database = db
      })
//      bindActor(classOf[WebhooksActor], "webhooks-actor")
      bindActorFactory(classOf[TxWebhookMessagingActor], classOf[TxWebhookMessagingActor.Factory])
      bindActorFactory(classOf[TxFilterAuthActor], classOf[TxFilterAuthActor.Factory])
      bindActorFactory(classOf[TxFilterNoAuthActor], classOf[TxFilterNoAuthActor.Factory])
    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  def fixture = new {

    implicit val timeout = Timeout(10.seconds)

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
//      .overrides(inject.bind(classOf[WebhookDao]).toInstance(webhookDao))
      .build()

    lazy val webhookDao = injector.instanceOf[WebhookDao]

    lazy val mockWsActor = system.actorOf(MockWebsocketActor.props(mockWs))

    lazy val txWatchActor =
      system.actorOf(TxFilterAuthActor.props(mockWsActor, mockMemPoolWatcher, mockUserManager))

    lazy val webhooksActor = {
      system.actorOf(
        WebhooksManagerActor.props(mockMemPoolWatcher,
          injector.instanceOf[HttpBackendSelection],
          injector.instanceOf[TxWebhookMessagingActor.Factory],
          injector.instanceOf[TxFilterNoAuthActor.Factory],
          injector.instanceOf[WebhookDao],
          injector.instanceOf[DatabaseExecutionContext]
        )
      )
    }

    lazy val webhookManagerMock = mock[WebhookManagerMock]
    lazy val mockWebhookManagerActor = system.actorOf(MockWebhookManagerActor.props(webhookManagerMock))
    lazy val webhooksManager = new WebhooksManager(
      mockMemPoolWatcher,
      webhookDao = webhookDao, actor = mockWebhookManagerActor)
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
        lazy val  get = f.mockPeerGroup
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
      val transaction = new Transaction(f.params)

      //noinspection SpellCheckingInspection
      val outputAddress1 = "1A5PFH8NdhLy1raKXKxFoqUgMAPUaqivqp"
      val value1 = 100L
      transaction.addOutput(Coin.valueOf(value1), Address.fromString(f.params, outputAddress1))

      //noinspection SpellCheckingInspection
      val outputAddress2 = "1G47mSr3oANXMafVrR8UC4pzV7FEAzo3r9"
      val value2 = 200L
      transaction.addOutput(Coin.valueOf(value2), Address.fromString(f.params, outputAddress2))

      broadcastTransaction(transaction)

      updateCapture.value should matchPattern {
        // noinspection SpellCheckingInspection
        case TxUpdate(_, totalValue, _, _, Seq(
                        TxInputOutput(Some(`outputAddress1`), Some(`value1`)),
                        TxInputOutput(Some(`outputAddress2`), Some(`value2`)),
                      ), Seq()) if totalValue == value1 + value2 =>
      }

      broadcastTransaction(f.transactions.head)
      updateCapture.value should matchPattern {
        case TxUpdate(_, 1000000, _, _, Seq(TxInputOutput(Some("1AJbsFZ64EpEfS5UAjAfcUG8pH8Jn3rn1F"), _)), Seq(_)) =>
      }

      // https://www.blockchain.com/btc/tx/6359f0868171b1d194cbee1af2f16ea598ae8fad666d9b012c8ed2b79a236ec4
      broadcastTransaction(f.transactions(1))
      updateCapture.value should matchPattern {
        // noinspection SpellCheckingInspection
        case TxUpdate("6359f0868171b1d194cbee1af2f16ea598ae8fad666d9b012c8ed2b79a236ec4", 300000000, _, _, Seq(
                        TxInputOutput(Some("1H8ANdafjpqYntniT3Ddxh4xPBMCSz33pj"), _),
                        TxInputOutput(Some("1Am9UTGfdnxabvcywYG2hvzr6qK8T3oUZT"), _)
                      ), Seq(
                        TxInputOutput(Some("15vScfMHNrXN4QvWe54q5hwfVoYwG79CS1"), _)
                      )) =>
      }

      // https://www.blockchain.com/btc/tx/73965c0ab96fa518f47df4f3e7201e0a36f163c4857fc28150d277caa8589259
      broadcastTransaction(f.transactions(2))
      updateCapture.value should matchPattern {
        // noinspection SpellCheckingInspection
        case TxUpdate("73965c0ab96fa518f47df4f3e7201e0a36f163c4857fc28150d277caa8589259", 923985, _, _,
                      Seq(
                        TxInputOutput(Some("1AyQnFZk9MbjLFXSWJ7euNbGhaNpjPvrSq"), _),
                        TxInputOutput(Some("bc1qwqdg6squsna38e46795at95yu9atm8azzmyvckulcc7kytlcckxswvvzej"), _)
                      ),
                      Seq(_)) =>
      }

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

    "WebhooksManager" should {

      "register and start all hooks stored in the database on initialisation" in {

        val f = fixture

        val hook1 = Webhook(new URI("http://test1"), 10)
        val hook2 = Webhook(new URI("http://test2"), 20)

        (f.webhookManagerMock.register _).expects(hook1).returning(Registered(hook1))
        (f.webhookManagerMock.register _).expects(hook2).returning(Registered(hook2))

        (f.webhookManagerMock.start _).expects(hook1.uri).returning(Started(hook1))
        (f.webhookManagerMock.start _).expects(hook2.uri).returning(Started(hook2))

        val init = for {
          _ <- database.run(
            DBIO.seq(
              Tables.schema.create,
              Tables.webhooks += hook1,
              Tables.webhooks += hook2
            )
          )
          _ <- f.webhooksManager.register(hook1)
          _ <- f.webhooksManager.register(hook2)
          response <- f.webhooksManager.init()
        } yield response

        whenReady(init) { _ => succeed }
      }
    }

    "WebhookManagerActor" should {

      def afterDbInit[T](fn: Unit => Future[T]): Future[T] = {
        for {
          _ <- database.run(DBIO.seq(Tables.schema.dropIfExists, Tables.schema.create))
          response <- fn()
        } yield response
      }

      "return WebhookNotRegistered when trying to start an unregistered hook" in {
        val f = fixture
        val uri = new URI("http://test")
        afterDbInit(_ => f.webhooksActor ? WebhooksManagerActor.Start(uri))
          .futureValue should matchPattern { case WebhookNotRegisteredException(`uri`) => }
      }

      "return Registered and record a new hook in the database when registering a new hook" in {
        val f = fixture
        val hook = Webhook(uri = new URI("http://test"), threshold = 100L)
        whenReady(afterDbInit(
          _ => for {
            response <- f.webhooksActor ? WebhooksManagerActor.Register(hook)
            contents <- db.run(Tables.webhooks.result)
          } yield (response, contents)
        )) {
          result =>
            result should matchPattern {
              case (WebhooksManagerActor.Registered(`hook`), Seq(`hook`)) =>
            }
        }
      }

      "correctly register, start, stop and restart a web hook" in {
        import WebhooksManagerActor._
        val f = fixture
        (f.mockMemPoolWatcher.addListener _).expects(*).twice()
        val uri = new URI("http://test")
        val hook = Webhook(uri, threshold = 100L)
        afterDbInit(_ => for {
          registered <- f.webhooksActor ? Register(hook)
          started <- f.webhooksActor ? Start(uri)
          stopped <- f.webhooksActor ? Stop(uri)
          _ <- Future {
            expectNoMessage()
          }
          restarted <- f.webhooksActor ? Start(uri)
          finalStop <- f.webhooksActor ? Stop(uri)
        } yield (registered, started, stopped, restarted, finalStop))
          .futureValue should matchPattern {
          case (Registered(`hook`), Started(`hook`), Stopped(`hook`),
          Started(`hook`), Stopped(`hook`)) =>
        }
      }
    }


  }

}
