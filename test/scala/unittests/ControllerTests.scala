package unittests

import actions.{Auth0ValidateJWTAction, SlackSignatureVerifyAction}
import actors.EncryptionActor.Encrypted
import actors.SlackSecretsActor.{InvalidSecretException, Unbind, ValidSecret}
import akka.actor.{ActorRef, ActorSystem}
import akka.testkit.TestKit
import akka.util.Timeout
import controllers._
import dao._
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener
import org.scalamock.handlers.CallHandler1
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import org.scalatest.{Assertion, BeforeAndAfterAll}
import play.api.http.Status.{OK, SERVICE_UNAVAILABLE, UNAUTHORIZED}
import play.api.inject.guice.GuiceableModule
import play.api.mvc.{AnyContentAsFormUrlEncoded, Result, Results}
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
import slack.BoltException
import slick.BtcPostgresProfile.api._
import slick.Tables
import unittests.Fixtures.{
  ActorGuiceFixtures,
  Auth0ActionFixtures,
  BlockChainWatcherFixtures,
  ConfigurationFixtures,
  DatabaseInitializer,
  EncryptionActorFixtures,
  EncryptionManagerFixtures,
  FakeApplication,
  MainNetParamsFixtures,
  MemPoolWatcherActorFixtures,
  MemPoolWatcherFixtures,
  MessagesFixtures,
  MockMailManagerFixtures,
  ProvidesTestBindings,
  SecretsManagerFixtures,
  SlackChatActorFixtures,
  SlackChatHookDaoFixtures,
  SlackEventsControllerFixtures,
  SlackManagerFixtures,
  SlackSignatureVerifierFixtures,
  SlickSlackTeamDaoFixtures,
  SlickSlackTeamFixtures,
  SlickSlashCommandFixtures,
  SlickSlashCommandHistoryDaoFixtures,
  TxWatchActorFixtures,
  UserFixtures,
  WebSocketFixtures
}
import util.Encodings.base64Encode

import scala.collection.compat.immutable.ArraySeq
import scala.concurrent.Future
import scala.concurrent.duration.DurationInt
import scala.util.{Failure, Success}

//noinspection TypeAnnotation
class ControllerTests
    extends TestKit(ActorSystem("meso-alert-controller-tests"))
    with AnyWordSpecLike
    with PostgresContainer
    with should.Matchers
    with ScalaFutures
    with Results
    with Eventually
    with BeforeAndAfterAll {

  // noinspection TypeAnnotation
  trait FixtureBindings extends ProvidesTestBindings with MessagesFixtures {
    val bindModule: GuiceableModule =
      new UnitTestModule(database, testExecutionContext, messagesApi)
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
        with MainNetParamsFixtures
        with BlockChainWatcherFixtures
        with MemPoolWatcherFixtures
        with TxWatchActorFixtures
        with MockMailManagerFixtures
        with ActorGuiceFixtures {

      val controller = new HomeController(
        Helpers.stubControllerComponents(),
        config,
        mockMailManager
      )

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .never()
      }

      def persistEmailDeliveryTest(
          attrs: Map[String, String],
          testType: String
      ): Assertion = {
        val request = emailFormSubmission(attrs)
        (mockMailManager.sendEmail _)
          .expects(*, *, *)
          .returning(Future.failed(new Exception("error")))
        val result = controller.postEmailForm().apply(request.withCSRFToken)
        val body = contentAsString(result)
        status(result) mustEqual OK
        body should include("value=\"testName\"")
        body should include("value=\"test@test.com\"")
        body should include(s">This is a test $testType message.<")
      }

      def failedEmailDeliveryTest(
          attrs: Map[String, String]
      ): Assertion = {
        val request = emailFormSubmission(attrs)
        (mockMailManager.sendEmail _)
          .expects(*, *, *)
          .returning(Future.failed(new Exception("error")))
        val result = controller.postEmailForm().apply(request.withCSRFToken)
        val body = contentAsString(result)
        status(result) mustEqual OK
        body should include("<div class=\"alert failed\" id=\"alert-failed\">")
      }

      def successfulEmailDeliveryTest(
          attrs: Map[String, String]
      ): Assertion = {
        val request = emailFormSubmission(attrs)
        (mockMailManager.sendEmail _)
          .expects(*, *, attrs("message"))
          .returning(Future(()))
        val result = controller.postEmailForm().apply(request.withCSRFToken)
        val body = contentAsString(result)
        status(result) mustEqual OK
        body should include(
          "<div class=\"alert success\" id=\"alert-success\">"
        )
      }
    }

    "render privacy policy" in new TestFixtures {
      val result = controller.privacyPolicy().apply(FakeRequest().withCSRFToken)
      status(result) mustEqual OK
    }

    "render terms and conditions" in new TestFixtures {
      val result =
        controller.termsAndConditions().apply(FakeRequest().withCSRFToken)
      status(result) mustEqual OK
    }

    "render website disclaimer" in new TestFixtures {
      val result =
        controller.websiteDisclaimer().apply(FakeRequest().withCSRFToken)
      status(result) mustEqual OK
    }

    "render feedback form without email delivery outcome message when using http method GET at /feedback" in new TestFixtures {
      val result = controller.feedbackPage().apply(FakeRequest().withCSRFToken)
      val body = contentAsString(result)
      status(result) mustEqual OK
      body should include("<form action=\"/email_form")
      body should not include "<div class=\"alert failed\" id=\"alert-failed\">"
      body should not include "<div class=\"alert success\" id=\"alert-success\">"
    }

    "send an email when feedback form is submitted with valid data" in
      new TestFixtures {
        (mockMailManager.sendEmail _)
          .expects(*, *, *)
          .returning(Future(()))
        val request = emailFormSubmission(feedbackFormAttrs)
        controller.postEmailForm().apply(request.withCSRFToken)
      }

    "notify user of successful email delivery - feedback" in new TestFixtures {
      successfulEmailDeliveryTest(feedbackFormAttrs)
    }

    "notify user of failed email delivery - feedback" in new TestFixtures {
      failedEmailDeliveryTest(feedbackFormAttrs)
    }

    "persist form data in case of a transient smtp failure" in new TestFixtures {
      persistEmailDeliveryTest(feedbackFormAttrs, "feedback")
    }

    "render support form without email delivery outcome message when using http method GET at /support" in new TestFixtures {
      val result = controller.supportPage().apply(FakeRequest().withCSRFToken)
      val body = contentAsString(result)
      status(result) mustEqual OK
      body should include("<form action=\"/email_form")
      body should not include "<div class=\"alert failed\" id=\"alert-failed\">"
      body should not include "<div class=\"alert success\" id=\"alert-success\">"
    }

    "send an email when support form is submitted with valid data" in
      new TestFixtures {
        (mockMailManager.sendEmail _)
          .expects(*, *, *)
          .returning(Future(()))

        val request = emailFormSubmission(supportFormAttrs)
        controller.postEmailForm().apply(request.withCSRFToken)
      }

    "notify user of successful email delivery - support" in new TestFixtures {
      successfulEmailDeliveryTest(supportFormAttrs)
    }

    "notify user of failed email delivery - support" in new TestFixtures {
      failedEmailDeliveryTest(supportFormAttrs)
    }

    "persist form data in case of a transient smtp failure - support" in new TestFixtures {
      persistEmailDeliveryTest(supportFormAttrs, "support")
    }
  }

  "SlackEventsController" should {
    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with MainNetParamsFixtures
        with MemPoolWatcherFixtures
        with BlockChainWatcherFixtures
        with ActorGuiceFixtures
        with MemPoolWatcherActorFixtures
        with EncryptionActorFixtures
        with EncryptionManagerFixtures
        with SlackChatHookDaoFixtures
        with SlackChatActorFixtures
        with SlickSlashCommandHistoryDaoFixtures
        with SlickSlashCommandFixtures
        with DatabaseInitializer
        with SlickSlackTeamDaoFixtures
        with SlackEventsControllerFixtures
        with SlackSignatureVerifierFixtures
        with SlackManagerFixtures
        with FakeApplication {

      val controller = new SlackEventsController(
        Helpers.stubControllerComponents(),
        hooksManager = new HooksManagerSlackChat(hookDao, hooksActor)
      )

      val action =
        fakeApplication.injector.instanceOf[SlackSignatureVerifyAction]
      val commandController = new SlackSlashCommandController(
        action,
        Helpers.stubControllerComponents(),
        slashCommandHistoryDao = slickSlashCommandHistoryDao,
        slackTeamDao = slickSlackTeamDao,
        hooksManager = new HooksManagerSlackChat(hookDao, hooksActor),
        messagesApi,
        mockSlackManagerService
      )

      memPoolWatcherExpectations((mockMemPoolWatcher.addListener _).expects(*))
        .anyNumberOfTimes()

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .never()
      }
    }

    "stop a running hook when channel is deleted" in new TestFixtures {
      afterDbInit {
        for {
          slackTeamEncrypted <- slickSlackTeamDao.toDB(
            SlackTeam(
              slashCommandTeamId,
              SlackUserId("test-user"),
              SlackBotId("test-bot"),
              testToken,
              "test-team",
              RegisteredUserId("test-user@test.domain")
            )
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
                  `channelId`,
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
                  `channelId`,
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
        with MainNetParamsFixtures
        with MemPoolWatcherFixtures
        with BlockChainWatcherFixtures
        with ActorGuiceFixtures
        with MemPoolWatcherActorFixtures
        with EncryptionActorFixtures
        with EncryptionManagerFixtures
        with SlackChatHookDaoFixtures
        with SlickSlashCommandHistoryDaoFixtures
        with SlickSlackTeamDaoFixtures
        with SlackChatActorFixtures
        with SlickSlashCommandFixtures
        with DatabaseInitializer
        with SlackSignatureVerifierFixtures
        with SlackManagerFixtures
        with FakeApplication {

//      encryptionManager.init()

      val action =
        fakeApplication.injector.instanceOf[SlackSignatureVerifyAction]
      val controller = new SlackSlashCommandController(
        action,
        Helpers.stubControllerComponents(),
        slashCommandHistoryDao = slickSlashCommandHistoryDao,
        slackTeamDao = slickSlackTeamDao,
        hooksManager = new HooksManagerSlackChat(hookDao, hooksActor),
        messagesApi,
        mockSlackManagerService
      )

      def slashCommand(
          makeFakeRequest: => FakeRequest[AnyContentAsFormUrlEncoded]
      ) =
        call(controller.slashCommand, makeFakeRequest)

      override def memPoolWatcherExpectations(
          ch: CallHandler1[ActorRef, Unit]
      ): CallHandler1[ActorRef, Unit] = {
        ch.atLeastOnce()
      }

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .anyNumberOfTimes()
      }

      val signatureVerifierExpectations =
        (mockSlackSignatureVerifierService.validate _)
          .expects(*, *, *)
          .anyNumberOfTimes()

      def setSignatureVerifierExpectations() =
        signatureVerifierExpectations.returning(Success("valid"))
      setSignatureVerifierExpectations()

      (mockSlackSignatureVerifierService.validate _)
        .expects(*, *, *)
        .returning(Success("valid"))
        .anyNumberOfTimes()

      def submitCommand(
          command: SlashCommand
      ): Future[(Result, Seq[SlackChatHookEncrypted])] = {
        afterDbInit {
          for {
            encrypted <- encryptionManager.encrypt(testToken.getBytes)
            _ <- db.run(
              Tables.slackTeams += SlackTeamEncrypted(
                slashCommandTeamId,
                SlackUserId("test-user"),
                SlackBotId("test-bot"),
                encrypted,
                "test-team",
                RegisteredUserId("test-user@test.domain")
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
                  `channelId`,
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
          slashCommandTeamId,
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
                  `channelId`,
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
          slashCommandTeamId,
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
        FakeRequest(POST, "/")
          .withFormUrlEncodedBody(("ssl_check", "1"))
          .withHeaders(ArraySeq.unsafeWrapArray(fakeSlackSignatureHeaders): _*)
      val result = call(controller.slashCommand, fakeRequest)
      status(result) mustEqual OK
    }

    "return correct message when issuing a valid /crypto-alert command" in new TestFixtures {
      val result = slashCommand {
        fakeRequestValid("/crypto-alert", "5")
      }
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.cryptoAlertNew"
    }

    "return reconfigure message when reconfiguring alerts" in new TestFixtures {
      val result = slashCommand {
        fakeRequestValid("/crypto-alert", "10")
      }
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.cryptoAlertReconfig"
    }

    "return error message when not supplying amount to /crypto-alert" in new TestFixtures {
      val result = slashCommand {
        fakeRequestValid("/crypto-alert", "")
      }
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.generalError"
    }

    "return correct message when asking for help with /crypto-alert" in new TestFixtures {
      val result = slashCommand {
        fakeRequestValid("/crypto-alert", "help")
      }
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.cryptoAlertHelp"
    }

    "return correct message when pausing alerts" in new TestFixtures {
      val result = slashCommand {
        fakeRequestValid("/pause-alerts", "")
      }
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.pauseAlerts"
    }

    "return error message when pausing alerts when there are no alerts active" in new TestFixtures {
      val result =
        call(
          controller.slashCommand,
          fakeRequestValid("/pause-alerts", "")
        )
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.pauseAlertsError"
    }

    "return correct message when asking for help with /pause-alerts" in new TestFixtures {
      val result = slashCommand {
        fakeRequestValid("/pause-alerts", "help")
      }
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.pauseAlertsHelp"
    }

    "return correct message when resuming alerts" in new TestFixtures {
      val result =
        call(
          controller.slashCommand,
          fakeRequestValid("/resume-alerts", "")
        )
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.resumeAlerts"
    }

    "return error message when resuming alerts when there are no alerts active" in new TestFixtures {
      val result =
        call(
          controller.slashCommand,
          fakeRequestValid("/resume-alerts", "")
        )
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.resumeAlertsError"
    }

    "return correct message when asking for help with /resume-alerts" in new TestFixtures {
      val result = slashCommand {
        fakeRequestValid("/resume-alerts", "help")
      }
      status(result) mustEqual OK
      contentAsString(result) mustEqual "slackResponse.resumeAlertsHelp"
    }

    "return an error message when no Slack signature is supplied" in new TestFixtures {
      val result = slashCommand {
        fakeRequestValidNoSignature("/pause-alerts", "")
      }
      status(result) mustEqual UNAUTHORIZED
    }

    "return an error message when an invalid Slack signature is supplied" in new TestFixtures {
      override def setSignatureVerifierExpectations() =
        signatureVerifierExpectations.returning(
          Failure(new Exception("Invalid signature"))
        )
      val result = slashCommand {
        fakeRequestValid("/pause-alerts", "")
      }
      status(result) mustEqual UNAUTHORIZED
      contentAsString(result) mustEqual "Invalid signature"
    }
  }

  "SlackAuthController" should {
    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with EncryptionActorFixtures
        with MainNetParamsFixtures
        with MemPoolWatcherFixtures
        with BlockChainWatcherFixtures
        with ActorGuiceFixtures
        with MemPoolWatcherActorFixtures
        with EncryptionManagerFixtures
        with SecretsManagerFixtures
        with SlackManagerFixtures
        with SlackChatHookDaoFixtures
        with SlickSlackTeamDaoFixtures
        with SlickSlackTeamFixtures
        with SlickSlashCommandFixtures
        with DatabaseInitializer
        with SlackSignatureVerifierFixtures
        with FakeApplication {

      val user: String = "test-user@test-domain.com"
      val slackAuthState: String =
        s"($user,${base64Encode(slackAuthSecret.data)})"
      val temporaryCode: String = "1"

      val controller = new SlackAuthController(
        config,
        slickSlackTeamDao,
        mockSlackSecretsManagerService,
        mockSlackManagerService,
        Helpers.stubControllerComponents()
      )

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .anyNumberOfTimes()
      }

    }

    "reject an invalid auth state" in new TestFixtures {

      (mockSlackManagerService.oauthV2Access _)
        .expects(*, *, *, *)
        .anyNumberOfTimes()

      (mockSlackSecretsManagerService.verifySecret _)
        .expects(*, *)
        .returning(
          Future.failed(
            InvalidSecretException(RegisteredUserId(user), slackAuthSecret)
          )
        )

      val result = call(
        controller
          .authRedirect(
            Some(temporaryCode),
            None,
            Some(slackAuthState)
          ),
        FakeRequest(GET, "")
      )
      status(result) mustEqual SERVICE_UNAVAILABLE
    }

    "display an error if authorisation fails" in new TestFixtures {

      (mockSlackManagerService.oauthV2Access _)
        .expects(*, *, *, *)
        .once()
        .returning(Future.failed(BoltException("access denied")))

      (mockSlackSecretsManagerService.verifySecret _)
        .expects(*, *)
        .once()
        .returning(Future { ValidSecret(RegisteredUserId(user)) })

      (mockSlackSecretsManagerService.unbind _)
        .expects(RegisteredUserId(user))
        .once()
        .returning(Future { Unbind(RegisteredUserId(user)) })

      afterDbInit {
        val result = call(
          controller
            .authRedirect(
              Some(temporaryCode),
              None,
              Some(slackAuthState)
            ),
          FakeRequest(GET, "")
        )

        status(result) mustEqual SERVICE_UNAVAILABLE

        db.run(
          Tables.slackTeams.result
        )
      }.futureValue should matchPattern { case Seq() =>
      }
    }

    "display successful installation page and record team to database if authorisation succeeds" in new TestFixtures {

      (mockSlackManagerService.oauthV2Access _)
        .expects(*, *, *, *)
        .once()
        .returning(Future { slackTeam })

      (mockSlackSecretsManagerService.verifySecret _)
        .expects(*, *)
        .once()
        .returning(Future { ValidSecret(RegisteredUserId(user)) })

      (mockSlackSecretsManagerService.unbind _)
        .expects(RegisteredUserId(user))
        .once()
        .returning(Future { Unbind(RegisteredUserId(user)) })

      afterDbInit {

        val result = call(
          controller
            .authRedirect(
              Some(temporaryCode),
              None,
              Some(slackAuthState)
            ),
          FakeRequest(GET, "")
        )

        status(result) mustEqual OK
        val body = contentAsString(result)
        body should include(
          "<h1>Success! Welcome to Block Insights.</h1>"
        )

        db.run(
          Tables.slackTeams.result
        )
      }.futureValue should matchPattern {
        case Seq(
              SlackTeamEncrypted(
                `teamId`,
                `teamUserId`,
                `botId`,
                Encrypted(_, _),
                `teamName`,
                `registeredUserId`
              )
            ) =>
      }
    }

    "redirect to home page when a users cancels installation" in new TestFixtures {
      afterDbInit {
        val result = call(
          controller
            .authRedirect(None, Some("access_denied"), Some(slackAuthState)),
          FakeRequest(GET, "?error=access_denied&state=")
        )
        val body = contentAsString(result)
        status(result) mustEqual OK
        body should include(
          "<title>Block Insights - Access free real-time mempool data</title>"
        )
        db.run(
          Tables.slackTeams.result
        )
      }.futureValue should matchPattern { case Seq() =>
      }
    }
  }

  "Auth0Controller" should {

    trait TestFixtures
        extends FixtureBindings
        with ConfigurationFixtures
        with SecretsManagerFixtures
        with MainNetParamsFixtures
        with BlockChainWatcherFixtures
        with MemPoolWatcherFixtures
        with ActorGuiceFixtures
        with MemPoolWatcherActorFixtures
        with SlackSignatureVerifierFixtures
        with FakeApplication
        with Auth0ActionFixtures {

      def mockAuth0Action: Auth0ValidateJWTAction = mockAuth0ActionAlwaysSuccess

      memPoolWatcherExpectations((mockMemPoolWatcher.addListener _).expects(*))
        .never()

      (mockSlackSecretsManagerService.generateSecret _)
        .expects(*)
        .returning(Future { slackAuthSecret })
        .anyNumberOfTimes()

      val controller =
        new Auth0Controller(
          mockAuth0Action,
          mockSlackSecretsManagerService,
          Helpers.stubControllerComponents(),
          config
        )

      val testUser = Some("test-user")

      override def peerGroupExpectations(): Unit = {
        (mockPeerGroup.start _).expects().once()
        (mockPeerGroup.setMaxConnections _).expects(*).once()
        (mockPeerGroup.addPeerDiscovery _).expects(*).once()
        (mockPeerGroup
          .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
          .expects(*)
          .anyNumberOfTimes()
      }
    }

    "return the correct configuration" in new TestFixtures {
      val result = call(controller.configuration(), FakeRequest(GET, ""))
      status(result) mustEqual OK
      val body = contentAsJson(result)
      body("clientId").as[String] mustEqual "test-client-id"
      body("domain").as[String] mustEqual "test-domain"
      body("audience").as[String] mustEqual "test-audience"
    }

    "return unauthorized when not supplying a JWT token to the secret endpoint" in new TestFixtures {
      override def mockAuth0Action: Auth0ValidateJWTAction =
        mockAuth0ActionAlwaysFail
      val request = FakeRequest(GET, "")
      val result = call(controller.secret(uid = testUser), request)
      status(result) mustEqual UNAUTHORIZED
    }

    "return unauthorized when supplying an invalid JWT token to the secret end point" in new TestFixtures {
      override def mockAuth0Action: Auth0ValidateJWTAction =
        mockAuth0ActionAlwaysFail
      val request =
        FakeRequest(GET, "").withHeaders(
          "Authorization" -> "Bearer fake-invalid"
        )
      val result = call(controller.secret(uid = testUser), request)
      status(result) mustEqual UNAUTHORIZED
    }

    "return a valid secret when supplying a valid user and JWT token to the secret end point" in new TestFixtures {
      override def mockAuth0Action: Auth0ValidateJWTAction =
        mockAuth0ActionAlwaysSuccess
      val request =
        FakeRequest(GET, "").withHeaders("Authorization" -> "Bearer fake-valid")
      val result = call(controller.secret(uid = testUser), request)
      status(result) mustEqual OK
      val body = contentAsJson(result)
      body("secret").as[String] mustEqual base64Encode(slackAuthSecret.data)
    }

  }
}
