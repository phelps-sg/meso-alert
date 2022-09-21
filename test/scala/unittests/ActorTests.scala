package unittests

import actors.AuthenticationActor.Auth
import actors.EncryptionActor.{Decrypted, Encrypt, Encrypted, Init}
import actors.MemPoolWatcherActor.{PeerGroupAlreadyStartedException, StartPeerGroup}
import actors.SlackSecretsActor.{GenerateSecret, Unbind, ValidSecret, VerifySecret}
import actors.{AuthenticationActor, BlockChainWatcherActor, HookAlreadyRegisteredException, HookAlreadyStartedException, HookNotRegisteredException, HookNotStartedException, Registered, Started, Stopped, TxHash, TxInputOutput, TxUpdate, Updated}
import akka.actor.{ActorRef, ActorSystem}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import akka.util.Timeout
import com.google.common.util.concurrent.ListenableFuture
import dao._
import org.bitcoinj.core._
import org.bitcoinj.core.listeners.{BlocksDownloadedEventListener, NewBestBlockListener, OnTransactionBroadcastListener}
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
import unittests.Fixtures.{ActorGuiceFixtures, BlockChainWatcherFixtures, ConfigurationFixtures, EncryptionActorFixtures, EncryptionManagerFixtures, HookActorTestLogic, MainNetParamsFixtures, MemPoolWatcherActorFixtures, MemPoolWatcherFixtures, MessagesFixtures, ProvidesTestBindings, SlackChatActorFixtures, SlackChatHookDaoFixtures, SlackSecretsActorFixtures, TransactionFixtures, TxPersistenceActorFixtures, TxUpdateFixtures, TxWatchActorFixtures, UserFixtures, WebSocketFixtures, WebhookActorFixtures, WebhookFixtures}

import java.net.URI
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.language.postfixOps
import scala.util.{Failure, Success}

// scalafix:off

//noinspection TypeAnnotation
class ActorTests
    extends TestKit(ActorSystem("meso-alert-test"))
    with AnyWordSpecLike
    with PostgresContainer
    with should.Matchers
    with MockFactory
    with ScalaFutures
    with BeforeAndAfterAll
    with ImplicitSender {

  // akka timeout
  implicit val akkaTimeout = Timeout(5.seconds)

  // whenReady timeout
  implicit override val patienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(5, Millis))

  trait FixtureBindings extends ProvidesTestBindings with MessagesFixtures {
    val bindModule: GuiceableModule =
      new UnitTestModule(database, testExecutionContext, messagesApi)
    val executionContext = testExecutionContext
    val actorSystem = system
    val timeout: Timeout = 20.seconds
    val db = database

    def wait(duration: FiniteDuration) = expectNoMessage(duration)
  }

  "MemPoolWatcherActor" should {

    trait TestFixtures
        extends FixtureBindings
        with MessagesFixtures
        with MemPoolWatcherFixtures
        with ConfigurationFixtures
        with ActorGuiceFixtures
        with MemPoolWatcherActorFixtures {

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup.start _).expects().once()
        (mockPeerGroup.setMaxConnections _).expects(*).once()
        (mockPeerGroup.addPeerDiscovery _).expects(*).once()
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .once()
        (mockPeerGroup
          .addBlocksDownloadedEventListener(_: BlocksDownloadedEventListener))
          .expects(*)
          .once()
      }
    }

    "return a successful acknowledgement when initialising the peer group" in new TestFixtures {
      (for {
        started <- memPoolWatcherActor ? StartPeerGroup
        _ <- Future {
          expectNoMessage()
        }
      } yield started).futureValue should matchPattern {
        case Success(Started(_: PeerGroup)) =>
      }
    }

    "return an error when initialising an already-initialised peer group" in new TestFixtures {
      (for {
        started <- memPoolWatcherActor ? StartPeerGroup
        error <- memPoolWatcherActor ? StartPeerGroup
        _ <- Future {
          expectNoMessage()
        }
      } yield (started, error)).futureValue should matchPattern {
        case (
              Success(Started(_: PeerGroup)),
              Failure(PeerGroupAlreadyStartedException)
            ) =>
      }
    }

  }

  "BlockChainWatcherActor" should {

    trait TestFixtures
        extends FixtureBindings
        with MainNetParamsFixtures
        with BlockChainWatcherFixtures {

      (mockBlockChain
        .addNewBestBlockListener(_: NewBestBlockListener))
        .expects(*)
    }

    "listen for new best blocks on startup" in new TestFixtures {
      val blockChainWatcherActor = actorSystem.actorOf(
        BlockChainWatcherActor.props(new MockBlockChainProvider())
      )
      expectNoMessage()
    }
  }

  // noinspection ZeroIndexToHead
  "MemPoolWatcher" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with MessagesFixtures
        with MemPoolWatcherFixtures
        with WebSocketFixtures
        with ActorGuiceFixtures
        with MemPoolWatcherActorFixtures
        with UserFixtures
        with TransactionFixtures {

      // Capture the listeners.  The second listener will be the txWatchActor
      lazy val listenerCapture = CaptureAll[OnTransactionBroadcastListener]()

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(capture(listenerCapture))
          .atLeastOnce()
      }

    }

    "send the correct TxUpdate message when a transaction update is received from " +
      "the bitcoinj peer group" in new TestFixtures {

        // Configure user to not filter events.
        (mockUser.filter _).expects(*).returning(true).atLeastOnce()
        // The user will authenticate  successfully with id "test".
        (mockUserManager.authenticate _)
          .expects("test")
          .returning(Success(mockUser))

        // Capture the update messages sent to the web socket for later verification.
        val updateCapture = CaptureAll[TxUpdate]()
        (mockWs.update _).expects(capture(updateCapture)).atLeastOnce()

        // This is required for raw types (see https://scalamock.org/user-guide/advanced_topics/).
        implicit val d = new Defaultable[ListenableFuture[_]] {
          override val default = null
        }

        val txWatchActor =
          actorSystem.actorOf(
            AuthenticationActor.props(
              mockWsActor,
              memPoolWatcher,
              mockUserManager
            )
          )

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

        // noinspection SpellCheckingInspection
        val outputAddress1 = "1A5PFH8NdhLy1raKXKxFoqUgMAPUaqivqp"
        val value1 = 100L
        transaction.addOutput(
          Coin.valueOf(value1),
          Address.fromString(mainNetParams, outputAddress1)
        )

        // noinspection SpellCheckingInspection
        val outputAddress2 = "1G47mSr3oANXMafVrR8UC4pzV7FEAzo3r9"
        val value2 = 200L
        transaction.addOutput(
          Coin.valueOf(value2),
          Address.fromString(mainNetParams, outputAddress2)
        )

        broadcastTransaction(transaction)

        updateCapture.value should matchPattern {
          // noinspection SpellCheckingInspection
          case TxUpdate(
                _,
                totalValue,
                _,
                _,
                Seq(
                  TxInputOutput(Some(`outputAddress1`), Some(`value1`)),
                  TxInputOutput(Some(`outputAddress2`), Some(`value2`))
                ),
                Seq()
              ) if totalValue == value1 + value2 =>
        }

        broadcastTransaction(transactions.head)
        updateCapture.value should matchPattern {
          case TxUpdate(
                _,
                1000000,
                _,
                _,
                Seq(
                  TxInputOutput(Some("1AJbsFZ64EpEfS5UAjAfcUG8pH8Jn3rn1F"), _)
                ),
                Seq(_)
              ) =>
        }

        // https://www.blockchain.com/btc/tx/6359f0868171b1d194cbee1af2f16ea598ae8fad666d9b012c8ed2b79a236ec4
        broadcastTransaction(transactions(1))
        updateCapture.value should matchPattern {
          // noinspection SpellCheckingInspection
          case TxUpdate(
                TxHash(
                  "6359f0868171b1d194cbee1af2f16ea598ae8fad666d9b012c8ed2b79a236ec4"
                ),
                300000000,
                _,
                _,
                Seq(
                  TxInputOutput(Some("1H8ANdafjpqYntniT3Ddxh4xPBMCSz33pj"), _),
                  TxInputOutput(Some("1Am9UTGfdnxabvcywYG2hvzr6qK8T3oUZT"), _)
                ),
                Seq(
                  TxInputOutput(Some("15vScfMHNrXN4QvWe54q5hwfVoYwG79CS1"), _)
                )
              ) =>
        }

        // https://www.blockchain.com/btc/tx/73965c0ab96fa518f47df4f3e7201e0a36f163c4857fc28150d277caa8589259
        broadcastTransaction(transactions(2))
        updateCapture.value should matchPattern {
          // noinspection SpellCheckingInspection
          case TxUpdate(
                TxHash(
                  "73965c0ab96fa518f47df4f3e7201e0a36f163c4857fc28150d277caa8589259"
                ),
                923985,
                _,
                _,
                Seq(
                  TxInputOutput(Some("1AyQnFZk9MbjLFXSWJ7euNbGhaNpjPvrSq"), _),
                  TxInputOutput(
                    Some(
                      "bc1qwqdg6squsna38e46795at95yu9atm8azzmyvckulcc7kytlcckxswvvzej"
                    ),
                    _
                  )
                ),
                Seq(_)
              ) =>
        }

      }
  }

  "TxPersistenceActor" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with MessagesFixtures
        with MemPoolWatcherFixtures
        with ActorGuiceFixtures
        with TxUpdateFixtures
        with TxPersistenceActorFixtures {

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .once()
      }
    }

    "register itself as a listener to the mem-pool" in new TestFixtures {
      (mockMemPoolWatcher.addListener _).expects(txPersistenceActor).once()
    }

    "record a new transaction update when it arrives" in new TestFixtures {
      (mockMemPoolWatcher.addListener _).expects(txPersistenceActor).once()
      (mockTransactionUpdateDao.record _)
        .expects(tx)
        .returning(Future(1))
        .once()

      txPersistenceActor ! tx
    }

    "retry to record a transaction if it fails" in new TestFixtures {
      (mockMemPoolWatcher.addListener _).expects(txPersistenceActor).once()
      (mockTransactionUpdateDao.record _)
        .expects(tx)
        .returning(Future.failed[Int](new Exception("error")))
      (mockTransactionUpdateDao.record _).expects(tx).returning(Future(1))

      txPersistenceActor ! tx
    }

    "terminate the actor if maxRetryCount (3) is reached" in new TestFixtures {
      (mockMemPoolWatcher.addListener _).expects(txPersistenceActor).once()
      (mockTransactionUpdateDao.record _)
        .expects(tx)
        .returning(
          Future.failed[Int](new Exception("error"))
        )
        .repeat(3)

      val probe = TestProbe()
      probe.watch(txPersistenceActor)

      txPersistenceActor ! tx
      probe.expectTerminated(txPersistenceActor, 5 seconds)
    }
  }

  "TxWatchActor" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with MessagesFixtures
        with MemPoolWatcherFixtures
        with WebSocketFixtures
        with ActorGuiceFixtures
        with UserFixtures
        with TxUpdateFixtures
        with TxWatchActorFixtures {

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .never()
      }
    }

    trait TestFixturesOneSubscriber extends TestFixtures {
      override def memPoolWatcherExpectations(
          ch: CallHandler1[ActorRef, Unit]
      ) = {
        ch.once()
      }

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .never()
      }
    }

    "provide updates when user is authenticated" in new TestFixturesOneSubscriber {

      (mockUser.filter _).expects(tx).returning(true)
      (mockUserManager.authenticate _)
        .expects("test")
        .returning(Success(mockUser))
      (mockWs.update _).expects(tx)

      txWatchActor ! Auth("test", "test")
      expectNoMessage()

      txWatchActor ! tx
      expectNoMessage()
    }

    "not provide updates when credentials are invalid" in new TestFixtures {

      (mockUserManager.authenticate _)
        .expects("test")
        .returning(Failure(InvalidCredentialsException))

      val probe = TestProbe()
      probe.watch(txWatchActor)

      txWatchActor ! Auth("test", "test")
      expectNoMessage()

      probe.expectTerminated(txWatchActor)

      txWatchActor ! tx
      expectNoMessage()
    }

    "only provide updates according to the user's filter" in new TestFixturesOneSubscriber {

      val tx1 = TxUpdate(
        TxHash("testHash1"),
        10,
        java.time.LocalDateTime.now(),
        isPending = true,
        List(),
        List()
      )
      val tx2 = TxUpdate(
        TxHash("testHash2"),
        1,
        java.time.LocalDateTime.now(),
        isPending = true,
        List(),
        List()
      )

      (mockUserManager.authenticate _)
        .expects("test")
        .returning(Success(mockUser))

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

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with MessagesFixtures
        with MemPoolWatcherFixtures
        with ActorGuiceFixtures
        with EncryptionActorFixtures
        with EncryptionManagerFixtures
        with SlackChatHookDaoFixtures
        with SlackChatActorFixtures
        with HookActorTestLogic[
          SlackChannelId,
          SlackChatHook,
          SlackChatHookEncrypted
        ] {

      encryptionManager.initialiseFuture()

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .never()
      }

      override def wait(duration: FiniteDuration): Unit =
        expectNoMessage(duration)
    }

    trait TestFixturesTwoSubscribers extends TestFixtures {
      override def memPoolWatcherExpectations(
          ch: CallHandler1[ActorRef, Unit]
      ) = {
        ch.twice()
      }
    }

    trait TestFixturesOneSubscriber extends TestFixtures {
      override def memPoolWatcherExpectations(
          ch: CallHandler1[ActorRef, Unit]
      ) = {
        ch.once()
      }
    }

    "return WebhookNotRegistered when trying to start an unregistered hook" in new TestFixtures {
      startHook().futureValue should matchPattern {
        case Failure(HookNotRegisteredException(`key`)) =>
      }
    }

    "return Registered and record a new hook in the database when registering a new hook" in new TestFixtures {
      registerHook().futureValue should matchPattern {
        case (Success(Registered(`hook`)), Seq(_: SlackChatHookEncrypted)) =>
      }
    }

    "return Updated when updating an existing hook" in new TestFixtures {
      updateHook().futureValue should matchPattern {
        case (Success(Updated(`newHook`)), Seq(_: SlackChatHookEncrypted)) =>
      }
    }

    "return an exception when stopping a hook that is not started" in new TestFixtures {
      stopHook().futureValue should matchPattern {
        case Failure(HookNotStartedException(`key`)) =>
      }
    }

    "return an exception when registering a pre-existing hook" in new TestFixtures {
      registerExistingHook().futureValue should matchPattern {
        case Failure(HookAlreadyRegisteredException(`hook`)) =>
      }
    }

    "return an exception when starting a hook that has already been started" in new TestFixturesOneSubscriber {
      registerStartStart().futureValue should matchPattern {
        case Failure(HookAlreadyStartedException(`key`)) =>
      }
    }

    "correctly register, start, stop and restart a web hook" in new TestFixturesTwoSubscribers {
      registerStartStopRestartStop().futureValue should matchPattern {
        case (
              Success(Registered(`hook`)),
              Success(Started(`hook`)),
              Success(Stopped(`hook`)),
              Success(Started(`hook`)),
              Success(Stopped(`hook`)),
              Seq(_: SlackChatHookEncrypted)
            ) =>
      }
    }
  }

  "WebhookManagerActor" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with MessagesFixtures
        with MemPoolWatcherFixtures
        with ActorGuiceFixtures
        with WebhookFixtures
        with WebhookActorFixtures
        with HookActorTestLogic[URI, Webhook, Webhook] {

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .never()
      }

    }

    trait TestFixturesTwoSubscribers
        extends TestFixtures
        with ActorGuiceFixtures {
      override def memPoolWatcherExpectations(
          ch: CallHandler1[ActorRef, Unit]
      ) = {
        ch.twice()
      }
    }

    trait TestFixturesOneSubscriber
        extends TestFixtures
        with ActorGuiceFixtures {
      override def memPoolWatcherExpectations(
          ch: CallHandler1[ActorRef, Unit]
      ) = {
        ch.once()
      }
    }

    "return WebhookNotRegistered when trying to start an unregistered hook" in new TestFixtures {
      startHook().futureValue should matchPattern {
        case Failure(HookNotRegisteredException(`key`)) =>
      }
    }

    "return Registered and record a new hook in the database when registering a new hook" in new TestFixtures {
      registerHook().futureValue should matchPattern {
        case (Success(Registered(`hook`)), Seq(`hook`)) =>
      }
    }

    "return Updated when updating an existing hook" in new TestFixtures {
      updateHook().futureValue should matchPattern {
        case (Success(Updated(`newHook`)), Seq(`newHook`)) =>
      }
    }

    "return an exception when stopping a hook that is not started" in new TestFixtures {
      stopHook().futureValue should matchPattern {
        case Failure(HookNotStartedException(`key`)) =>
      }
    }

    "return an exception when registering a pre-existing hook" in new TestFixtures {
      registerExistingHook().futureValue should matchPattern {
        case Failure(HookAlreadyRegisteredException(`hook`)) =>
      }
    }

    "return an exception when starting a hook that has already been started" in new TestFixturesOneSubscriber {
      registerStartStart().futureValue should matchPattern {
        case Failure(HookAlreadyStartedException(`key`)) =>
      }
    }

    "correctly register, start, stop and restart a web hook" in new TestFixturesTwoSubscribers {
      registerStartStopRestartStop().futureValue should matchPattern {
        case (
              Success(Registered(`hook`)),
              Success(Started(`hook`)),
              Success(Stopped(`hook`)),
              Success(Started(`hook`)),
              Success(Stopped(`hook`)),
              Seq(`stoppedHook`)
            ) =>
      }
    }
  }

  "EncryptionActor" should {

    trait TestFixtures extends FixtureBindings with EncryptionActorFixtures

    "decrypt ciphertext to plain text" in new TestFixtures {
      (for {
        init <- encryptionActor ? Init(secret)
        encryptedResult <- encryptionActor ? Encrypt(plainTextBinary)
        decryptedResult <- {
          encryptedResult match {
            case Success(encrypted: Encrypted) =>
              encryptionActor ? encrypted
            case _ =>
              fail(s"Invalid message from actor $encryptedResult")
          }
        }
      } yield (init, encryptedResult, decryptedResult)).futureValue match {
        case (
              Success(_),
              Success(Encrypted(_, _)),
              Success(decrypted: Decrypted)
            ) =>
          decrypted.asString shouldEqual plainText
        case _ =>
          fail()
      }
    }

    "not reuse the same nonce for subsequent messages" in new TestFixtures {
      (for {
        init <- encryptionActor ? Init(secret)
        encryptedResult <- encryptionActor ? Encrypt(plainTextBinary)
        secondEncryptedResult <- encryptionActor ? Encrypt(
          secondPlainTextBinary
        )
      } yield (
        init,
        encryptedResult,
        secondEncryptedResult
      )).futureValue should matchPattern {
        case (
              Success(_),
              Success(Encrypted(_, nonce1)),
              Success(Encrypted(_, nonce2))
            ) if !(nonce1 sameElements nonce2) =>
      }
    }

    "give an error if encrypting without initialisation" in new TestFixtures {
      (for {
        error <- encryptionActor ? Encrypt(plainTextBinary)
      } yield error).futureValue should matchPattern { case Failure(_) => }
    }

    "give an error if initialising an already initialised actor" in new TestFixtures {
      (for {
        init <- encryptionActor ? Init(secret)
        error <- encryptionActor ? Init(secret)
      } yield (init, error)).futureValue should matchPattern {
        case (Success(_), Failure(_)) =>
      }
    }

  }

  "SlackSecretsActor" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with MessagesFixtures
        with MemPoolWatcherFixtures
        with ActorGuiceFixtures
        with EncryptionActorFixtures
        with EncryptionManagerFixtures
        with SlackSecretsActorFixtures {

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .never()
      }

    }

    "create a new secret" in new TestFixtures {
      (for {
        secret <- slackSecretsActor ? GenerateSecret(userId)
      } yield secret).futureValue should matchPattern {
        case Success(Secret(_)) =>
      }
    }

    "create unique secrets for different users" in new TestFixtures {
      (for {
        secret1 <- slackSecretsActor ? GenerateSecret(userId)
        secret2 <- slackSecretsActor ? GenerateSecret(anotherUserId)
      } yield (secret1, secret2)).futureValue should matchPattern {
        case (Success(Secret(s1)), Success(Secret(s2)))
            if !(s1 sameElements s2) =>
      }
    }

    "verify an existing secret" in new TestFixtures {
      (for {
        secret <- (slackSecretsActor ? GenerateSecret(userId))
        verified <- {
          secret match {
            case Success(secret: Secret) =>
              slackSecretsActor ? VerifySecret(userId, secret)
          }
        }
      } yield verified).futureValue should matchPattern {
        case Success(ValidSecret(`userId`)) =>
      }
    }

    "reject a secret that has been unbound" in new TestFixtures {
      (for {
        secret <- (slackSecretsActor ? GenerateSecret(userId))
        _ <- slackSecretsActor ? Unbind(userId)
        verified <- {
          secret match {
            case Success(secret: Secret) =>
              slackSecretsActor ? VerifySecret(userId, secret)
          }
        }
      } yield verified).futureValue should matchPattern { case Failure(_) =>
      }
    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }

}
