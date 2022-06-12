import actors.TxFilterAuthActor.{Auth, TxInputOutput}
import actors.{HookAlreadyRegisteredException, HookAlreadyStartedException, HookNotRegisteredException, HookNotStartedException, HooksManagerActorWeb, Register, Registered, Start, Started, Stop, Stopped, TxFilterAuthActor, TxFilterNoAuthActor, TxMessagingActorWeb, TxUpdate}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
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
import org.scalamock.handlers.CallHandler1
import org.scalamock.matchers.ArgCapture.CaptureAll
import org.scalamock.scalatest.MockFactory
import org.scalamock.util.Defaultable
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should.Matchers
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.inject
import play.api.inject.Injector
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
import scala.util.{Failure, Success}

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
      case Start(uri: URI) =>
        mock.start(uri)
        sender ! Success(Started(hooks(uri)))
      case Register(hook: Webhook) =>
        mock.register(hook)
        hooks(hook.uri) = hook
        sender ! Success(Registered(hook))
      case Stop(uri: URI) =>
        mock.stop(uri)
        sender ! Success(Stopped(hooks(uri)))
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
      bindActorFactory(classOf[TxMessagingActorWeb], classOf[TxMessagingActorWeb.Factory])
      bindActorFactory(classOf[TxFilterAuthActor], classOf[TxFilterAuthActor.Factory])
      bindActorFactory(classOf[TxFilterNoAuthActor], classOf[TxFilterNoAuthActor.Factory])
    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

  def afterDbInit[T](fn: => Future[T]): Future[T] = {
    for {
      _ <- database.run(DBIO.seq(Tables.schema.dropIfExists, Tables.schema.create))
      response <- fn
    } yield response
  }

  trait MemPoolWatcherFixtures {
    val mockMemPoolWatcher = mock[MemPoolWatcherService]
    val mainNetParams = MainNetParams.get()

    class MockPeerGroup extends PeerGroup(mainNetParams)
    val mockPeerGroup = mock[MockPeerGroup]

    def memPoolWatcherExpectations(ch: CallHandler1[ActorRef, Unit]): ch.Derived = {
      ch.never()
    }
    memPoolWatcherExpectations((mockMemPoolWatcher.addListener _).expects(*))
  }

  trait TransactionFixtures {
    val mainNetParams: MainNetParams
    lazy val transactions = Json.parse(Source.fromResource("tx_valid.json").getLines.mkString)
      .as[Array[JsArray]].map(_.value).filter(_.size > 1)
      .map(testData => mainNetParams.getDefaultSerializer.makeTransaction(HEX.decode(testData(1).as[String].toLowerCase)))
  }

  trait WebSocketFixtures {
    val mockWs = mock[WebSocketMock]
    val mockWsActor = system.actorOf(MockWebsocketActor.props(mockWs))
  }

  trait ActorGuiceFixtures {
    def builder = new GuiceInjectorBuilder()
      .bindings(new TestModule)
      .overrides(inject.bind(classOf[ActorSystem]).toInstance(system))

    val injector = builder.build()
  }

  trait MemPoolGuiceFixtures extends ActorGuiceFixtures {
    val mockMemPoolWatcher: MemPoolWatcherService

    override def builder = super.builder
      .overrides(inject.bind(classOf[MemPoolWatcherService]).toInstance(mockMemPoolWatcher))
  }

  trait WebhookDaoFixtures {
    val injector: Injector
    val webhookDao = injector.instanceOf[WebhookDao]
  }

  trait WebhookActorFixtures {
    val injector: Injector
    val webhooksActor = {
      system.actorOf(
        HooksManagerActorWeb.props(
          injector.instanceOf[TxMessagingActorWeb.Factory],
          injector.instanceOf[TxFilterNoAuthActor.Factory],
          injector.instanceOf[WebhookDao],
          injector.instanceOf[DatabaseExecutionContext]
        )
      )
    }
  }

  trait UserFixtures {
    val mockUser = mock[User]
    val mockUserManager = mock[UserManagerService]
  }

  trait TxWatchActorFixtures {
    val mockWsActor: ActorRef
    val mockMemPoolWatcher: MemPoolWatcherService
    val mockUserManager: UserManagerService

    val txWatchActor =
      system.actorOf(TxFilterAuthActor.props(mockWsActor, mockMemPoolWatcher, mockUserManager))
  }

  trait WebhookManagerFixtures {
    val webhookDao: WebhookDao
    val webhookManagerMock = mock[WebhookManagerMock]
    val mockWebhookManagerActor = system.actorOf(MockWebhookManagerActor.props(webhookManagerMock))
    val webhooksManager = new HooksManagerWeb(webhookDao, actor = mockWebhookManagerActor)
  }

  trait WebhooksActorFixtures {
    val injector: Injector
    val webhooksActor = {
      system.actorOf(
        HooksManagerActorWeb.props(
          injector.instanceOf[TxMessagingActorWeb.Factory],
          injector.instanceOf[TxFilterNoAuthActor.Factory],
          injector.instanceOf[WebhookDao],
          injector.instanceOf[DatabaseExecutionContext]
        )
      )
    }
  }

  //noinspection ZeroIndexToHead
  "MemPoolWatcher" should {

    trait TextFixtures extends MemPoolWatcherFixtures
      with WebSocketFixtures with MemPoolGuiceFixtures with UserFixtures with TransactionFixtures

    "send the correct TxUpdate message when a transaction update is received from " +
      "the bitcoinj peer group" in new TextFixtures {

      // Configure user to not filter events.
      (mockUser.filter _).expects(*).returning(true).atLeastOnce()
      // The user will authenticate  successfully with id "test".
      (mockUserManager.authenticate _).expects("test").returning(mockUser)

      // Capture the update messages sent to the web socket for later verification.
      val updateCapture = CaptureAll[TxUpdate]()
      (mockWs.update _).expects(capture(updateCapture)).atLeastOnce()

      // This is required for raw types (see https://scalamock.org/user-guide/advanced_topics/).
      implicit val d = new Defaultable[ListenableFuture[_]] {
        override val default = null
      }

      // Capture the listeners.  The second listener will be the txWatchActor
      val listenerCapture = CaptureAll[OnTransactionBroadcastListener]()
      (mockPeerGroup.addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
        .expects(capture(listenerCapture)).atLeastOnce()

      val memPoolWatcher = new MemPoolWatcher(new PeerGroupSelection() {
        val params = mainNetParams
        lazy val get = mockPeerGroup
      })

      val txWatchActor =
        system.actorOf(TxFilterAuthActor.props(mockWsActor, memPoolWatcher, mockUserManager))

      memPoolWatcher.addListener(txWatchActor)

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
      val transaction = new Transaction(mainNetParams)

      //noinspection SpellCheckingInspection
      val outputAddress1 = "1A5PFH8NdhLy1raKXKxFoqUgMAPUaqivqp"
      val value1 = 100L
      transaction.addOutput(Coin.valueOf(value1), Address.fromString(mainNetParams, outputAddress1))

      //noinspection SpellCheckingInspection
      val outputAddress2 = "1G47mSr3oANXMafVrR8UC4pzV7FEAzo3r9"
      val value2 = 200L
      transaction.addOutput(Coin.valueOf(value2), Address.fromString(mainNetParams, outputAddress2))

      broadcastTransaction(transaction)

      updateCapture.value should matchPattern {
        // noinspection SpellCheckingInspection
        case TxUpdate(_, totalValue, _, _, Seq(
        TxInputOutput(Some(`outputAddress1`), Some(`value1`)),
        TxInputOutput(Some(`outputAddress2`), Some(`value2`)),
        ), Seq()) if totalValue == value1 + value2 =>
      }

      broadcastTransaction(transactions.head)
      updateCapture.value should matchPattern {
        case TxUpdate(_, 1000000, _, _, Seq(TxInputOutput(Some("1AJbsFZ64EpEfS5UAjAfcUG8pH8Jn3rn1F"), _)), Seq(_)) =>
      }

      // https://www.blockchain.com/btc/tx/6359f0868171b1d194cbee1af2f16ea598ae8fad666d9b012c8ed2b79a236ec4
      broadcastTransaction(transactions(1))
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
      broadcastTransaction(transactions(2))
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

    trait TestFixtures extends MemPoolWatcherFixtures
      with WebSocketFixtures with ActorGuiceFixtures with UserFixtures with TxWatchActorFixtures

    trait TestFixturesOneSubscriber extends TestFixtures {
      override def memPoolWatcherExpectations(ch: CallHandler1[ActorRef, Unit]) = {
        ch.once()
      }
    }

    "provide updates when user is authenticated" in new TestFixturesOneSubscriber {

      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true, List(), List())

      (mockUser.filter _).expects(tx).returning(true)
      (mockUserManager.authenticate _).expects("test").returning(mockUser)
      (mockWs.update _).expects(tx)

      txWatchActor ! Auth("test", "test")
      expectNoMessage()

      txWatchActor ! tx
      expectNoMessage()
    }

    "not provide updates when credentials are invalid" in new TestFixtures {

      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true, List(), List())

      (mockUserManager.authenticate _).expects("test").throws(InvalidCredentialsException())

      val probe = TestProbe()
      probe.watch(txWatchActor)

      txWatchActor ! Auth("test", "test")
      expectNoMessage()

      probe.expectTerminated(txWatchActor)

      txWatchActor ! tx
      expectNoMessage()
    }

    "only provide updates according to the user's filter" in new TestFixturesOneSubscriber {

      val tx1 = TxUpdate("testHash1", 10, DateTime.now(), isPending = true, List(), List())
      val tx2 = TxUpdate("testHash2", 1, DateTime.now(), isPending = true, List(), List())

      (mockUserManager.authenticate _).expects("test").returning(mockUser)

      txWatchActor ! Auth("test", "test")
      expectNoMessage()

      (mockUser.filter _).expects(tx1).returning(true)
      (mockWs.update _).expects(tx1).once()
      txWatchActor ! tx1
      expectNoMessage()

      (mockUser.filter _).expects(tx2).returning(false)
      txWatchActor ! tx2
      expectNoMessage()
    }
  }

  "WebhooksManager" should {

    trait TestFixtures extends ActorGuiceFixtures
      with WebhookDaoFixtures with WebhookActorFixtures with WebhookManagerFixtures

    "register and start all hooks stored in the database on initialisation" in new TestFixtures {

      val hook1 = Webhook(new URI("http://test1"), 10)
      val hook2 = Webhook(new URI("http://test2"), 20)

      (webhookManagerMock.register _).expects(hook1).returning(Success(Registered(hook1)))
      (webhookManagerMock.register _).expects(hook2).returning(Success(Registered(hook2)))

      (webhookManagerMock.start _).expects(hook1.uri).returning(Success(Started(hook1)))
      (webhookManagerMock.start _).expects(hook2.uri).returning(Success(Started(hook2)))

      val init = for {
        _ <- database.run(
          DBIO.seq(
            Tables.schema.create,
            Tables.webhooks += hook1,
            Tables.webhooks += hook2
          )
        )
        _ <- webhooksManager.register(hook1)
        _ <- webhooksManager.register(hook2)
        response <- webhooksManager.init()
      } yield response

      whenReady(init) { _ => succeed }
    }
  }

  "WebhookManagerActor" should {

    trait TestFixtures extends MemPoolWatcherFixtures with ActorGuiceFixtures with WebhookActorFixtures

    trait TestFixturesTwoSubscribers extends TestFixtures with MemPoolGuiceFixtures {
      override def memPoolWatcherExpectations(ch: CallHandler1[ActorRef, Unit]) = {
        ch.twice()
      }
    }

    "return WebhookNotRegistered when trying to start an unregistered hook" in new TestFixtures {
      val uri = new URI("http://test")
      afterDbInit {
        webhooksActor ? Start(uri)
      }.futureValue should matchPattern { case Failure(HookNotRegisteredException(`uri`)) => }
    }

    "return Registered and record a new hook in the database when registering a new hook" in new TestFixtures {
      val hook = Webhook(uri = new URI("http://test"), threshold = 100L)
      afterDbInit {
        for {
          response <- webhooksActor ? Register(hook)
          dbContents <- db.run(Tables.webhooks.result)
        } yield (response, dbContents)
      }.futureValue should matchPattern { case (Success(Registered(`hook`)), Seq(`hook`)) => }
    }

    "return an exception when stopping a hook that is not started" in new TestFixtures {
      val uri = new URI("http://test")
      afterDbInit {
        webhooksActor ? Stop(uri)
      }.futureValue should matchPattern { case Failure(HookNotStartedException(`uri`)) => }
    }

    "return an exception when registering a pre-existing hook" in new TestFixtures {
      val uri = new URI("http://test")
      val hook = Webhook(uri, 10)
      afterDbInit {
        for {
          _ <- db.run(Tables.webhooks += hook)
          registered <- webhooksActor ? Register(hook)
        } yield registered
      }.futureValue should matchPattern { case Failure(HookAlreadyRegisteredException(`hook`)) => }
    }

    "return an exception when starting a hook that has already been started" in new TestFixtures {
      val uri = new URI("http://test")
      val hook = Webhook(uri, 10)
      afterDbInit {
        for {
          registered <- webhooksActor ? Register(hook)
          started <- webhooksActor ? Start(hook.uri)
          error <- webhooksActor ? Start(hook.uri)
        } yield error
      }.futureValue should matchPattern { case Failure(HookAlreadyStartedException(`uri`)) => }
    }

    "correctly register, start, stop and restart a web hook" in new TestFixturesTwoSubscribers {
      val uri = new URI("http://test")
      val hook = Webhook(uri, threshold = 100L)
      afterDbInit {
        for {
          registered <- webhooksActor ? Register(hook)
          started <- webhooksActor ? Start(uri)
          stopped <- webhooksActor ? Stop(uri)
          _ <- Future {
            expectNoMessage()
          }
          restarted <- webhooksActor ? Start(uri)
          finalStop <- webhooksActor ? Stop(uri)
        } yield (registered, started, stopped, restarted, finalStop)
      }.futureValue should matchPattern {
        case (
          Success(Registered(`hook`)),
          Success(Started(`hook`)),
          Success(Stopped(`hook`)),
          Success(Started(`hook`)),
          Success(Stopped(`hook`))) =>
      }
    }
  }

}
