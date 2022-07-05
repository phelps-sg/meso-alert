package unittests

import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestKit
import akka.util.Timeout
import controllers.SlackSlashCommandController
import dao.{SlackChannel, SlackChatHook, SlackTeam, SlashCommand}
import org.scalamock.handlers.CallHandler1
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.http.Status.OK
import play.api.inject.guice.GuiceableModule
import play.api.mvc.Result
import play.api.test.Helpers
import play.api.test.Helpers.contentAsString
import postgres.PostgresContainer
import services.HooksManagerSlackChat
import slick.BtcPostgresProfile.api._
import slick.Tables
import unittests.Fixtures.{ActorGuiceFixtures, DatabaseInitializer, MemPoolWatcherFixtures, SlackChatActorFixtures, SlackChatHookDaoFixtures, SlickSlackTeamDaoFixtures, SlickSlashCommandFixtures, SlickSlashCommandHistoryDaoFixtures}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

//noinspection TypeAnnotation
class ControllerTests extends TestKit(ActorSystem("meso-alert-dao-tests"))
  with AnyWordSpecLike
  with PostgresContainer
  with should.Matchers
  with ScalaFutures
  with BeforeAndAfterAll {

  //noinspection TypeAnnotation
  trait FixtureBindings {
    val bindModule: GuiceableModule = new UnitTestModule(database, testExecutionContext)
    val executionContext = testExecutionContext
    val actorSystem = system
    val db = database
    implicit val timeout: Timeout = 20.seconds
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(5, Millis))

  "SlackSlashCommandController" should {

    trait TestFixtures extends FixtureBindings
      with MemPoolWatcherFixtures with ActorGuiceFixtures with SlickSlashCommandHistoryDaoFixtures
      with SlickSlackTeamDaoFixtures with SlackChatActorFixtures
      with SlackChatHookDaoFixtures with SlickSlashCommandFixtures with DatabaseInitializer {

      val controller = new SlackSlashCommandController(Helpers.stubControllerComponents(),
        slashCommandHistoryDao = slickSlashCommandHistoryDao,
        slackTeamDao = slickSlackTeamDao, hooksManager = new HooksManagerSlackChat(hookDao, hooksActor)) {
        override def init(): Future[Unit] = {
          // Do not call createIfNotExists because of issue #68
          Future { () }
        }
      }


      override def memPoolWatcherExpectations(ch: CallHandler1[ActorRef, Unit]): CallHandler1[ActorRef, Unit] = {
        ch.once()
      }

      def submitCommand(command: SlashCommand): Future[(Result, Seq[SlackChatHook])] = {
        afterDbInit {
          for {
            _ <- db.run(
              Tables.slackTeams += SlackTeam(teamId, "test-user", "test-bot", "test-token", "test-team")
            )
            response <- controller.process(command)
            dbContents <- db.run(Tables.slackChatHooks.result)
          } yield (response, dbContents)
        }
      }
    }

    "start a chat hook specifying BTC" in new TestFixtures {

      val cryptoAlertCommand =
        SlashCommand(None, channelId, "/crypto-alert", "5 BTC",
          teamDomain, teamId, channelName, userId, userName, isEnterpriseInstall, None)

      val futureValue = submitCommand(cryptoAlertCommand).futureValue

      futureValue should matchPattern {
       case (_: Result, Seq(SlackChatHook(SlackChannel(`channelId`), "test-token", 500000000, true))) =>
      }

      futureValue match {
        case (result: Result, _) =>
          result.header.status mustEqual OK
      }
    }

    "start a chat hook without specifying currency" in new TestFixtures {
      val cryptoAlertCommand =
        SlashCommand(None, channelId, "/crypto-alert", "5",
          teamDomain, teamId, channelName, userId, userName, isEnterpriseInstall, None)

      val futureValue = submitCommand(cryptoAlertCommand).futureValue

      futureValue should matchPattern {
        case (_: Result, Seq(SlackChatHook(SlackChannel(`channelId`), "test-token", 500000000, true))) =>
      }

      futureValue match {
        case (result: Result, _) =>
          result.header.status mustEqual OK
      }
    }

    "return a friendly error message if non-BTC currency is specified" in new TestFixtures {
      val cryptoAlertCommand =
        SlashCommand(None, channelId, "/crypto-alert", "5 ETH",
          teamDomain, teamId, channelName, userId, userName, isEnterpriseInstall, None)

      val response = controller.process(cryptoAlertCommand)

      contentAsString(response) mustEqual "I currently only provide alerts for BTC, but other currencies are coming soon."
    }

  }

}
