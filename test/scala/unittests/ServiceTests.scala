package unittests

import actors.EncryptionActor.{Decrypted, Encrypted}
import actors.{Registered, Started}
import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import dao.{Satoshi, Webhook}
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.inject.guice.GuiceableModule
import postgres.PostgresContainer
import slick.BtcPostgresProfile.api._
import slick.Tables
import slick.dbio.DBIO
import unittests.Fixtures.{ActorGuiceFixtures, BlockChainWatcherFixtures, ClockFixtures, ConfigurationFixtures, DatabaseExecutionContextSingleton, DatabaseGuiceFixtures, EncryptionActorFixtures, EncryptionManagerFixtures, MainNetParamsFixtures, MemPoolWatcherFixtures, MessagesFixtures, ProvidesTestBindings, WebManagerFixtures, WebhookActorFixtures, WebhookDaoFixtures, WebhookFixtures, WebhookManagerFixtures}

import java.net.URI
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.Success

//noinspection TypeAnnotation
class ServiceTests
    extends TestKit(ActorSystem("meso-alert-dao-tests"))
    with AnyWordSpecLike
    with PostgresContainer
    with should.Matchers
    with ScalaFutures
    with BeforeAndAfterAll {

  // noinspection TypeAnnotation
  trait FixtureBindings
      extends ProvidesTestBindings
      with MessagesFixtures
      with ClockFixtures {
    val bindModule: GuiceableModule =
      new UnitTestModule(database, testExecutionContext, messagesApi, clock)
    val executionContext = testExecutionContext
    val actorSystem = system
    val db = database
    val timeout: Timeout = 20.seconds
  }

  // whenReady timeout
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(5, Seconds), interval = Span(5, Millis))

  "EncryptionManager" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with EncryptionActorFixtures
        with DatabaseGuiceFixtures
        with EncryptionManagerFixtures

    "decrypt ciphertext to the correct plain text" in new TestFixtures {
      (for {
        encrypted <- encryptionManager.encrypt(plainTextBinary)
        decrypted <- encryptionManager.decrypt(encrypted)
      } yield (encrypted, decrypted)).futureValue match {
        case (Encrypted(_, _), decrypted: Decrypted) =>
          decrypted.asString shouldEqual plainText
      }
    }

  }

  "WebhooksManager" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with DatabaseExecutionContextSingleton
        with MemPoolWatcherFixtures
        with MainNetParamsFixtures
        with BlockChainWatcherFixtures
        with WebManagerFixtures
        with ActorGuiceFixtures
        with WebhookDaoFixtures
        with WebhookFixtures
        with WebhookActorFixtures
        with WebhookManagerFixtures {

      (mockPeerGroup
        .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
        .expects(*)
        .never()

    }

    "register and start all running hooks stored in the database on initialisation" in new TestFixtures {

      val hook1 =
        Webhook(new URI("http://test1"), Satoshi(10), isRunning = true)
      val hook2 =
        Webhook(new URI("http://test2"), Satoshi(20), isRunning = true)
      val hook3 =
        Webhook(new URI("http://test3"), Satoshi(30), isRunning = false)

      val hooks = List(hook1, hook2, hook3)

      hooks foreach { hook =>
        (webhookManagerMock.register _)
          .expects(hook)
          .returning(Success(Registered(hook)))
      }

      hooks.filter(_.isRunning).foreach { hook =>
        (webhookManagerMock.start _)
          .expects(hook.uri)
          .returning(Success(Started(hook)))
      }

      val init = for {
        _ <- database.run(
          DBIO.seq(
            Tables.webhooks.delete,
            Tables.webhooks ++= hooks
          )
        )
        _ <- Future.sequence(hooks.map(webhooksManager.register))
        response <- webhooksManager.initialiseFuture()
      } yield response

      init.futureValue shouldBe ()

    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
}
