package unittests

import actors.AuthenticationActor.{Auth, TxInputOutput}
import actors.MemPoolWatcherActor.{PeerGroupAlreadyStartedException, StartPeerGroup}
import actors.{AuthenticationActor, HookAlreadyRegisteredException, HookAlreadyStartedException, HookNotRegisteredException, HookNotStartedException, Registered, Started, Stopped, TxUpdate, Updated}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.github.nscala_time.time.Imports.DateTime
import com.google.common.util.concurrent.ListenableFuture
import dao._
import org.bitcoinj.core._
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener
import org.scalamock.handlers.CallHandler1
import org.scalamock.matchers.ArgCapture.CaptureAll
import org.scalamock.scalatest.MockFactory
import org.scalamock.util.Defaultable
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.inject.guice.GuiceableModule
import postgres.PostgresContainer
import services._
import unittests.Fixtures.{ActorGuiceFixtures, HookActorTestLogic, MemPoolWatcherActorFixtures, MemPoolWatcherFixtures, SlackChatActorFixtures, TransactionFixtures, TxWatchActorFixtures, UserFixtures, WebSocketFixtures, WebhookActorFixtures, WebhookFixtures}

import java.net.URI
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

// scalafix:off

//noinspection TypeAnnotation
class ActorTests extends TestKit(ActorSystem("meso-alert-test"))
  with AnyWordSpecLike
  with PostgresContainer
  with should.Matchers
  with MockFactory
  with ScalaFutures
  with BeforeAndAfterAll
  with ImplicitSender {

  // akka timeout
  implicit val akkaTimeout = Timeout(5.seconds)

//  lazy val dbBackend: JdbcBackend.Database = database.asInstanceOf[JdbcBackend.Database]
//  val testExecutionContext = ExecutionContext.fromExecutor(Executors.newFixedThreadPool(3))

  // whenReady timeout
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(5, Millis))

  trait FixtureBindings {
    val bindModule: GuiceableModule = new UnitTestModule(database, testExecutionContext)
    val executionContext = testExecutionContext
    val actorSystem = system
    val timeout: Timeout = 20.seconds
    val db = database

    def wait(duration: FiniteDuration) = expectNoMessage(duration)
  }

  "MemPoolWatcherActor" should {

    trait TestFixtures extends FixtureBindings with ActorGuiceFixtures with
      MemPoolWatcherFixtures with MemPoolWatcherActorFixtures {

      (mockPeerGroup.start _).expects().once()
      (mockPeerGroup.setMaxConnections _).expects(*).once()
      (mockPeerGroup.addPeerDiscovery _).expects(*).once()
      (mockPeerGroup.addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener)).expects(*).once()
    }

    "return a successful acknowledgement when initialising the peer group" in new TestFixtures {
      (for {
        started <- memPoolWatcherActor ? StartPeerGroup
        _ <- Future {
          expectNoMessage()
        }
      } yield started)
        .futureValue should matchPattern { case Success(Started(_: PeerGroup)) => }
    }

    "return an error when initialising an already-initialised peer group" in new TestFixtures {
      (for {
        started <- memPoolWatcherActor ? StartPeerGroup
        error <- memPoolWatcherActor ? StartPeerGroup
        _ <- Future {
          expectNoMessage()
        }
      } yield (started, error))
        .futureValue should matchPattern {
        case (Success(Started(_: PeerGroup)), Failure(PeerGroupAlreadyStartedException)) =>
      }
    }

  }

  //noinspection ZeroIndexToHead
  "MemPoolWatcher" should {

    trait TextFixtures extends FixtureBindings with MemPoolWatcherFixtures
      with WebSocketFixtures with ActorGuiceFixtures with MemPoolWatcherActorFixtures with UserFixtures
      with TransactionFixtures {
    }

    "send the correct TxUpdate message when a transaction update is received from " +
      "the bitcoinj peer group" in new TextFixtures {

      // Configure user to not filter events.
      (mockUser.filter _).expects(*).returning(true).atLeastOnce()
      // The user will authenticate  successfully with id "test".
      (mockUserManager.authenticate _).expects("test").returning(Success(mockUser))

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

      val txWatchActor =
        actorSystem.actorOf(AuthenticationActor.props(mockWsActor, memPoolWatcher, mockUserManager))

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

    trait TestFixtures extends FixtureBindings with MemPoolWatcherFixtures
      with WebSocketFixtures with ActorGuiceFixtures with UserFixtures with TxWatchActorFixtures

    trait TestFixturesOneSubscriber extends TestFixtures {
      override def memPoolWatcherExpectations(ch: CallHandler1[ActorRef, Unit]) = {
        ch.once()
      }
    }

    "provide updates when user is authenticated" in new TestFixturesOneSubscriber {

      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true, List(), List())

      (mockUser.filter _).expects(tx).returning(true)
      (mockUserManager.authenticate _).expects("test").returning(Success(mockUser))
      (mockWs.update _).expects(tx)

      txWatchActor ! Auth("test", "test")
      expectNoMessage()

      txWatchActor ! tx
      expectNoMessage()
    }

    "not provide updates when credentials are invalid" in new TestFixtures {

      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true, List(), List())

      (mockUserManager.authenticate _).expects("test").returning(Failure(InvalidCredentialsException))

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

      (mockUserManager.authenticate _).expects("test").returning(Success(mockUser))

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

  "SlackChatManagerActor" should {

    trait TestFixtures extends FixtureBindings
      with MemPoolWatcherFixtures with ActorGuiceFixtures
      with SlackChatActorFixtures with HookActorTestLogic[SlackChannel, SlackChatHook] {
      override def wait(duration: FiniteDuration): Unit = expectNoMessage(duration)
    }

    trait TestFixturesTwoSubscribers extends TestFixtures {
      override def memPoolWatcherExpectations(ch: CallHandler1[ActorRef, Unit]) = {
        ch.twice()
      }
    }

    trait TestFixturesOneSubscriber extends TestFixtures {
      override def memPoolWatcherExpectations(ch: CallHandler1[ActorRef, Unit]) = {
        ch.once()
      }
    }

    "return WebhookNotRegistered when trying to start an unregistered hook" in new TestFixtures {
      startHook().futureValue should matchPattern { case Failure(HookNotRegisteredException(`key`)) => }
    }

    "return Registered and record a new hook in the database when registering a new hook" in new TestFixtures {
      registerHook().futureValue should matchPattern { case (Success(Registered(`hook`)), Seq(`hook`)) => }
    }

    "return Updated when updating an existing hook" in new TestFixtures {
      updateHook().futureValue should matchPattern { case (Success(Updated(`newHook`)), Seq(`newHook`)) => }
    }

    "return an exception when stopping a hook that is not started" in new TestFixtures {
      stopHook().futureValue should matchPattern { case Failure(HookNotStartedException(`key`)) => }
    }

    "return an exception when registering a pre-existing hook" in new TestFixtures {
      registerExistingHook().futureValue should matchPattern { case Failure(HookAlreadyRegisteredException(`hook`)) => }
    }

    "return an exception when starting a hook that has already been started" in new TestFixturesOneSubscriber {
      registerStartStart().futureValue should matchPattern { case Failure(HookAlreadyStartedException(`key`)) => }
    }

    "correctly register, start, stop and restart a web hook" in new TestFixturesTwoSubscribers {
      registerStartStopRestartStop().futureValue should matchPattern {
        case (
          Success(Registered(`hook`)),
          Success(Started(`hook`)),
          Success(Stopped(`hook`)),
          Success(Started(`hook`)),
          Success(Stopped(`hook`)),
          Seq(`stoppedHook`)) =>
      }
    }
  }

  "WebhookManagerActor" should {

    trait TestFixtures extends FixtureBindings with
      MemPoolWatcherFixtures with ActorGuiceFixtures with WebhookFixtures
      with WebhookActorFixtures with HookActorTestLogic[URI, Webhook] {
    }

    trait TestFixturesTwoSubscribers extends TestFixtures with ActorGuiceFixtures {
      override def memPoolWatcherExpectations(ch: CallHandler1[ActorRef, Unit]) = {
        ch.twice()
      }
    }

    trait TestFixturesOneSubscriber extends TestFixtures with ActorGuiceFixtures {
      override def memPoolWatcherExpectations(ch: CallHandler1[ActorRef, Unit]) = {
        ch.once()
      }
    }

    "return WebhookNotRegistered when trying to start an unregistered hook" in new TestFixtures {
      startHook().futureValue should matchPattern { case Failure(HookNotRegisteredException(`key`)) => }
    }

    "return Registered and record a new hook in the database when registering a new hook" in new TestFixtures {
      registerHook().futureValue should matchPattern { case (Success(Registered(`hook`)), Seq(`hook`)) => }
    }

    "return Updated when updating an existing hook" in new TestFixtures {
      updateHook().futureValue should matchPattern { case (Success(Updated(`newHook`)), Seq(`newHook`)) => }
    }

    "return an exception when stopping a hook that is not started" in new TestFixtures {
      stopHook().futureValue should matchPattern { case Failure(HookNotStartedException(`key`)) => }
    }

    "return an exception when registering a pre-existing hook" in new TestFixtures {
      registerExistingHook().futureValue should matchPattern { case Failure(HookAlreadyRegisteredException(`hook`)) => }
    }

    "return an exception when starting a hook that has already been started" in new TestFixturesOneSubscriber {
      registerStartStart().futureValue should matchPattern { case Failure(HookAlreadyStartedException(`key`)) => }
    }

    "correctly register, start, stop and restart a web hook" in new TestFixturesTwoSubscribers {
      registerStartStopRestartStop().futureValue should matchPattern {
        case (
          Success(Registered(`hook`)),
          Success(Started(`hook`)),
          Success(Stopped(`hook`)),
          Success(Started(`hook`)),
          Success(Stopped(`hook`)),
          Seq(`stoppedHook`)) =>
      }
    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

}
