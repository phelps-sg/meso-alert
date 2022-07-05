package unittests

import actors.{HookAlreadyRegisteredException, HookAlreadyStartedException, HookNotRegisteredException, HookNotStartedException, Registered, Started, Stopped, Updated}
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestKit
import akka.util.Timeout
import dao.{SlackChannel, SlackChatHook, Webhook}
import org.scalamock.handlers.CallHandler1
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.inject.guice.GuiceableModule
import postgres.PostgresContainer
import slick.BtcPostgresProfile.api._
import slick.Tables
import slick.dbio.DBIO
import unittests.Fixtures.{ActorGuiceFixtures, HookActorTestLogic, MemPoolWatcherFixtures, SlackChatActorFixtures, WebhookActorFixtures, WebhookDaoFixtures, WebhookFixtures, WebhookManagerFixtures}

import java.net.URI
import scala.concurrent.Future
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.util.{Failure, Success}

//noinspection TypeAnnotation
class ServiceTests extends TestKit(ActorSystem("meso-alert-dao-tests"))
  with AnyWordSpecLike
  with PostgresContainer
  with should.Matchers
  with ScalaFutures {

  //noinspection TypeAnnotation
  trait FixtureBindings {
    val bindModule: GuiceableModule = new UnitTestModule(database, testExecutionContext)
    val executionContext = testExecutionContext
    val actorSystem = system
    val db = database
    val timeout: Timeout = 20.seconds
  }

  // whenReady timeout
  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(5, Millis))

  "WebhooksManager" should {

    trait TestFixtures extends FixtureBindings with MemPoolWatcherFixtures with ActorGuiceFixtures
      with WebhookDaoFixtures with WebhookFixtures with WebhookActorFixtures with WebhookManagerFixtures

    "register and start all running hooks stored in the database on initialisation" in new TestFixtures {

      val hook1 = Webhook(new URI("http://test1"), 10, isRunning = true)
      val hook2 = Webhook(new URI("http://test2"), 20, isRunning = true)
      val hook3 = Webhook(new URI("http://test3"), 30, isRunning = false)

      val hooks = List(hook1, hook2, hook3)

      hooks foreach { hook =>
        (webhookManagerMock.register _).expects(hook).returning(Success(Registered(hook)))
      }

      hooks.filter(_.isRunning).foreach { hook =>
        (webhookManagerMock.start _).expects(hook.uri).returning(Success(Started(hook)))
      }

      val init = for {
        _ <- database.run(
          DBIO.seq(
            Tables.schema.create,
            Tables.webhooks ++= hooks,
          )
        )
        _ <- Future.sequence(hooks.map(webhooksManager.register))
        response <- webhooksManager.init()
      } yield response

      init.futureValue should matchPattern {
        case Seq(Started(`hook1`), Started(`hook2`)) =>
      }

    }
  }

}