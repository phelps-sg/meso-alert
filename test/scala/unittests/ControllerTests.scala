package unittests

import actors.EncryptionActor.Encrypted
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestKit
import akka.util.Timeout
import controllers.{
  Auth0Controller,
  HomeController,
  SlackAuthController,
  SlackEventsController,
  SlackSlashCommandController
}
import dao._
import org.scalamock.handlers.CallHandler1
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.http.Status.OK
import play.api.inject.guice.GuiceableModule
import play.api.mvc.{Result, Results}
import play.api.test.CSRFTokenHelper._
import play.api.test.Helpers.{
  GET,
  POST,
  call,
  contentAsJson,
  contentAsString,
  status,
  writeableOf_AnyContentAsEmpty,
  writeableOf_AnyContentAsFormUrlEncoded
}
import play.api.test.{FakeRequest, Helpers}
import postgres.PostgresContainer
import services.HooksManagerSlackChat
import slick.BtcPostgresProfile.api._
import slick.Tables
import unittests.Fixtures.{
  ActorGuiceFixtures,
  ConfigurationFixtures,
  DatabaseInitializer,
  EncryptionActorFixtures,
  EncryptionManagerFixtures,
  MemPoolWatcherFixtures,
  MockMailManagerFixtures,
  SecretsManagerFixtures,
  ProvidesTestBindings,
  SlackChatActorFixtures,
  SlackChatHookDaoFixtures,
  SlackEventsControllerFixtures,
  SlickSlackTeamDaoFixtures,
  SlickSlashCommandFixtures,
  SlickSlashCommandHistoryDaoFixtures,
  TxWatchActorFixtures,
  UserFixtures,
  WebSocketFixtures
}

import scala.concurrent.Future
import scala.concurrent.duration.DurationInt

//noinspection TypeAnnotation
class ControllerTests
    extends TestKit(ActorSystem("meso-alert-dao-tests"))
    with AnyWordSpecLike
    with PostgresContainer
    with should.Matchers
    with ScalaFutures
    with Results
    with Eventually
    with BeforeAndAfterAll {

  // noinspection TypeAnnotation
  trait FixtureBindings extends ProvidesTestBindings {
    val bindModule: GuiceableModule =
      new UnitTestModule(database, testExecutionContext)
    val executionContext = testExecutionContext
    val actorSystem = system
    val db = database
    implicit val timeout: Timeout = 20.seconds
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(2, Seconds), interval = Span(5, Millis))

  "HomeController" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with WebSocketFixtures
        with UserFixtures
        with MemPoolWatcherFixtures
        with TxWatchActorFixtures
        with MockMailManagerFixtures
        with ActorGuiceFixtures {

      val controller = new HomeController(
        Helpers.stubControllerComponents(),
        config,
        mockMailManager
      )
    }

    "render feedback form without email delivery outcome message when using http method GET at /feedback" in new TestFixtures {
      val result = controller.feedbackPage().apply(FakeRequest().withCSRFToken)
      val body = contentAsString(result)
      status(result) mustEqual OK
      body should include("<form action=\"/feedback")
      body should not include "<div class=\"alert failed\" id=\"alert-failed\">"
      body should not include "<div class=\"alert success\" id=\"alert-success\">"
    }

    "send an email when feedback form is submitted with valid data" in
      new TestFixtures {
        (mockMailManager.sendEmail _)
          .expects(expectedValidEmailSubject, feedbackMessage)
          .returning(Future(()))

        val request = fakeRequestFormSubmission
        controller.create().apply(request.withCSRFToken)
      }

    "notify user of successful email delivery" in new TestFixtures {
      (mockMailManager.sendEmail _)
        .expects(expectedValidEmailSubject, feedbackMessage)
        .returning(Future(()))

      val request = fakeRequestFormSubmission
      val result = controller.create().apply(request.withCSRFToken)
      val body = contentAsString(result)
      status(result) mustEqual OK
      body should include("<div class=\"alert success\" id=\"alert-success\">")
    }

    "notify user of failed email delivery" in new TestFixtures {
      (mockMailManager.sendEmail _)
        .expects(expectedValidEmailSubject, feedbackMessage)
        .returning(Future.failed(new Exception("error")))

      val request = fakeRequestFormSubmission
      val result = controller.create().apply(request.withCSRFToken)
      val body = contentAsString(result)
      status(result) mustEqual OK
      body should include("<div class=\"alert failed\" id=\"alert-failed\">")
    }

    "persist form data in case of a transient smtp failure" in new TestFixtures {
      (mockMailManager.sendEmail _)
        .expects(expectedValidEmailSubject, feedbackMessage)
        .returning(Future.failed(new Exception("error")))
      val request = fakeRequestFormSubmission
      val result = controller.create().apply(request.withCSRFToken)
      val body = contentAsString(result)
      status(result) mustEqual OK
      body should include("value=\"testName\"")
      body should include("value=\"test@test.com\"")
      body should include(">This is a test feedback message.<")
    }
  }

  "SlackEventsController" should {
    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with MemPoolWatcherFixtures
        with ActorGuiceFixtures
        with EncryptionActorFixtures
        with EncryptionManagerFixtures
        with SlackChatHookDaoFixtures
        with SlackChatActorFixtures
        with SlickSlashCommandHistoryDaoFixtures
        with SlickSlashCommandFixtures
        with DatabaseInitializer
        with SlickSlackTeamDaoFixtures
        with SlackEventsControllerFixtures {

      val controller = new SlackEventsController(
        Helpers.stubControllerComponents(),
        hooksManager = new HooksManagerSlackChat(hookDao, hooksActor)
      )

      val commandController = new SlackSlashCommandController(
        Helpers.stubControllerComponents(),
        slashCommandHistoryDao = slickSlashCommandHistoryDao,
        slackTeamDao = slickSlackTeamDao,
        hooksManager = new HooksManagerSlackChat(hookDao, hooksActor),
        messagesApi
      )

      memPoolWatcherExpectations((mockMemPoolWatcher.addListener _).expects(*))
        .anyNumberOfTimes()
    }

    "stop a running hook when channel is deleted" in new TestFixtures {
      afterDbInit {
        for {
          slackTeamEncrypted <- slickSlackTeamDao.toDB(
            SlackTeam(teamId, "test-user", "test-bot", testToken, "test-team")
          )
          _ <- db.run(
            Tables.slackTeams += slackTeamEncrypted
          )
          response <- commandController.process(cryptoAlertCommand)
          dbContents <- db.run(Tables.slackChatHooks.result)
        } yield (response, dbContents)
      }.futureValue should matchPattern {
        case (
              _: Result,
              Seq(
                SlackChatHookEncrypted(
                  SlackChannel(`channelId`),
                  _: Encrypted,
                  500000000,
                  true
                )
              )
            ) =>
      }

      val fakeRequest =
        FakeRequest(POST, "/").withBody(deleteChannelRequestBody)
      val result = controller.eventsAPI().apply(fakeRequest)
      status(result) mustEqual OK
      Thread.sleep(3000)
      val dbContents = db.run(Tables.slackChatHooks.result).futureValue

      eventually {
        dbContents should matchPattern {
          case Vector(
                SlackChatHookEncrypted(
                  SlackChannel(`channelId`),
                  _: Encrypted,
                  500000000,
                  false
                )
              ) =>
        }
      }
    }
  }

  "SlackSlashCommandController" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with MemPoolWatcherFixtures
        with ActorGuiceFixtures
        with EncryptionActorFixtures
        with EncryptionManagerFixtures
        with SlackChatHookDaoFixtures
        with SlickSlashCommandHistoryDaoFixtures
        with SlickSlackTeamDaoFixtures
        with SlackChatActorFixtures
        with SlickSlashCommandFixtures
        with DatabaseInitializer {

//      encryptionManager.init()

      val controller = new SlackSlashCommandController(
        Helpers.stubControllerComponents(),
        slashCommandHistoryDao = slickSlashCommandHistoryDao,
        slackTeamDao = slickSlackTeamDao,
        hooksManager = new HooksManagerSlackChat(hookDao, hooksActor),
        messagesApi
      )

      override def memPoolWatcherExpectations(
          ch: CallHandler1[ActorRef, Unit]
      ): CallHandler1[ActorRef, Unit] = {
        ch.once()
      }

      def submitCommand(
          command: SlashCommand
      ): Future[(Result, Seq[SlackChatHookEncrypted])] = {
        afterDbInit {
          for {
            encrypted <- encryptionManager.encrypt(testToken.getBytes)
            _ <- db.run(
              Tables.slackTeams += SlackTeamEncrypted(
                teamId,
                "test-user",
                "test-bot",
                encrypted,
                "test-team"
              )
            )
            response <- controller.process(command)
            dbContents <- db.run(Tables.slackChatHooks.result)
            _ <- db.run(Tables.slackChatHooks.delete)
          } yield (response, dbContents)
        }
      }
    }

    "start a chat hook specifying BTC" in new TestFixtures {
      val futureValue = submitCommand(cryptoAlertCommand).futureValue

      futureValue should matchPattern {
        case (
              _: Result,
              Seq(
                SlackChatHookEncrypted(
                  SlackChannel(`channelId`),
                  _: Encrypted,
                  500000000,
                  true
                )
              )
            ) =>
      }

      futureValue match {
        case (result: Result, _) =>
          result.header.status mustEqual OK
      }
    }

    "start a chat hook without specifying currency" in new TestFixtures {
      override val cryptoAlertCommand =
        SlashCommand(
          None,
          channelId,
          "/crypto-alert",
          "5",
          teamDomain,
          teamId,
          channelName,
          userId,
          userName,
          isEnterpriseInstall,
          None
        )

      val futureValue = submitCommand(cryptoAlertCommand).futureValue

      futureValue should matchPattern {
        case (
              _: Result,
              Seq(
                SlackChatHookEncrypted(
                  SlackChannel(`channelId`),
                  _: Encrypted,
                  500000000,
                  true
                )
              )
            ) =>
      }

      futureValue match {
        case (result: Result, _) =>
          result.header.status mustEqual OK
      }
    }

    "return a friendly error message if non-BTC currency is specified" in new TestFixtures {
      override val cryptoAlertCommand =
        SlashCommand(
          None,
          channelId,
          "/crypto-alert",
          "5 ETH",
          teamDomain,
          teamId,
          channelName,
          userId,
          userName,
          isEnterpriseInstall,
          None
        )

      val response = controller.process(cryptoAlertCommand)

      contentAsString(
        response
      ) mustEqual "I currently only provide alerts for BTC, but other currencies are coming soon."
    }

    "return http status 200 when receiving an ssl_check due to url change" in new TestFixtures {
      val fakeRequest =
        FakeRequest(POST, "/").withFormUrlEncodedBody(("ssl_check", "1"))
      val result = call(controller.slashCommand, fakeRequest)
      status(result) mustEqual OK
    }

    "return correct message when issuing a valid /crypto-alert command" in new TestFixtures {
      val result =
        call(controller.slashCommand, fakeRequestValid("/crypto-alert", "5"))
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.cryptoAlertNew"
    }

    "return reconfigure message when reconfiguring alerts" in new TestFixtures {
      val result =
        call(controller.slashCommand, fakeRequestValid("/crypto-alert", "10"))
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.cryptoAlertReconfig"
    }

    "return error message when not supplying amount to /crypto-alert" in new TestFixtures {
      val result =
        call(controller.slashCommand, fakeRequestValid("/crypto-alert", ""))
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.generalError"
    }

    "return correct message when pausing alerts" in new TestFixtures {
      val result =
        call(controller.slashCommand, fakeRequestValid("/pause-alerts", ""))
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.pauseAlerts"
    }

    "return error message when pausing alerts when there are no alerts active" in new TestFixtures {
      val result =
        call(controller.slashCommand, fakeRequestValid("/pause-alerts", ""))
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.pauseAlertsError"
    }

    "return correct message when resuming alerts" in new TestFixtures {
      val result =
        call(controller.slashCommand, fakeRequestValid("/resume-alerts", ""))
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.resumeAlerts"
    }

    "return error message when resuming alerts when there are no alerts active" in new TestFixtures {
      val result =
        call(controller.slashCommand, fakeRequestValid("/resume-alerts", ""))
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.resumeAlertsError"
    }
  }

  "SlackAuthController" should {
    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with EncryptionActorFixtures
        with MemPoolWatcherFixtures
        with ActorGuiceFixtures
        with EncryptionManagerFixtures
        with SlackChatHookDaoFixtures
        with SlickSlackTeamDaoFixtures
        with SlickSlashCommandFixtures {

      val dummySlackAuthState: String =
        "auth0|630b26c75e81f50a0f401c2a,cOG eZb TOmPgYBBJMKZqI6bb429elqsRVVsT3qY/lObgjYH5FCYQX7hflRJbFs soYpa4kB9v5 00ZiSZhTLw==)"

      val controller = new SlackAuthController(
        config,
        slickSlackTeamDao,
        Helpers.stubControllerComponents()
      )
    }

    "redirect to home page when a users cancels installation" in new TestFixtures {
      val result = call(
        controller
          .authRedirect(None, Some("access_denied"), dummySlackAuthState),
        FakeRequest(GET, "?error=access_denied&state=")
      )
      val body = contentAsString(result)
      status(result) mustEqual OK
      body should include(
        "<title>Block Insights - Access free real-time mempool data</title>"
      )
    }
  }

  "Auth0Controller" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with SecretsManagerFixtures {
      (mockSlackSecretsManagerService.generateSecret _)
        .expects(*)
        .returning(Future { secret })
        .anyNumberOfTimes()
      val controller =
        new Auth0Controller(
          mockSlackSecretsManagerService,
          Helpers.stubControllerComponents(),
          config
        )
    }

    "return the correct configuration" in new TestFixtures {
      val result = call(controller.configuration, FakeRequest(GET, ""))
      status(result) mustEqual OK
      val body = contentAsJson(result)
      body("clientId").as[String] mustEqual "test-client-id"
      body("domain").as[String] mustEqual "test-domain"
      body("audience").as[String] mustEqual "test-audience"
    }
  }
}
