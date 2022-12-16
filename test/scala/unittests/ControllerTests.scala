package unittests

import actions.Auth0ValidateJWTAction
import actors.EncryptionActor.Encrypted
import actors.SlackSecretsActor.{InvalidSecretException, Unbind, ValidSecret}
import akka.actor.ActorSystem
import akka.testkit.TestKit
import akka.util.Timeout
import controllers._
import dao._
import org.bitcoinj.core.listeners.OnTransactionBroadcastListener
import org.scalatest.Assertion
import org.scalatest.concurrent.{Eventually, ScalaFutures}
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
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
  ClockFixtures,
  ConfigurationFixtures,
  DatabaseInitializer,
  DefaultBodyParserFixtures,
  EncryptionActorFixtures,
  EncryptionManagerFixtures,
  HooksManagerActorSlackChatFixtures,
  MainNetParamsFixtures,
  MemPoolWatcherActorFixtures,
  MemPoolWatcherFixtures,
  MessagesFixtures,
  MockMailManagerFixtures,
  ProvidesTestBindings,
  SecretsManagerFixtures,
  SlackChatHookDaoFixtures,
  SlackChatHookFixtures,
  SlackEventsControllerFixtures,
  SlackManagerFixtures,
  SlackSignatureVerifierFixtures,
  SlackSlashCommandControllerFixtures,
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
import scala.util.Failure

//noinspection TypeAnnotation
class ControllerTests
    extends TestKit(ActorSystem("meso-alert-controller-tests"))
    with AnyWordSpecLike
    with PostgresContainer
    with should.Matchers
    with ScalaFutures
    with Results
    with Eventually {

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
    implicit val timeout: Timeout = 20.seconds
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(10, Seconds), interval = Span(200, Millis))

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

//      override def peerGroupExpectations(): Unit = {
      (mockPeerGroup
        .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
        .expects(*)
        .never()
//      }

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
          attrs: Map[String, String],
          destinationEmail: String
      ): Assertion = {
        val request = emailFormSubmission(attrs)
        val formData = emailFormData(attrs)
        (mockMailManager.sendEmail _)
          .expects(destinationEmail, formData.subjectLine, formData.message)
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
      successfulEmailDeliveryTest(
        feedbackFormAttrs,
        config.get[String]("email.destination")
      )
    }

    "notify user of successful email delivery - support" in new TestFixtures {
      successfulEmailDeliveryTest(
        supportFormAttrs,
        config.get[String]("email.destinationSupport")
      )
    }

    "notify user of failed email delivery - support" in new TestFixtures {
      failedEmailDeliveryTest(supportFormAttrs)
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
        with HooksManagerActorSlackChatFixtures
        with SlickSlashCommandHistoryDaoFixtures
        with SlickSlashCommandFixtures
        with DatabaseInitializer
        with SlickSlackTeamDaoFixtures
        with SlackEventsControllerFixtures
        with SlackSignatureVerifierFixtures
        with SlackManagerFixtures
        with DefaultBodyParserFixtures
        with SlackSlashCommandControllerFixtures {

      override def privateChannel: Boolean = false

      val eventsController = new SlackEventsController(
        Helpers.stubControllerComponents(),
        hooksManager = new HooksManagerSlackChat(hookDao, hooksActor),
        slackSignatureVerifyAction
      )

      (mockPeerGroup
        .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
        .expects(*)
        .never()
    }

    "stop a running hook when channel is deleted" in new TestFixtures {

      afterDbInit {
        for {
          slackTeamEncrypted <- slickSlackTeamDao.toDB(
            SlackTeam(
              slashCommandTeamId,
              SlackUserId("test-user"),
              SlackBotId("test-bot"),
              token1,
              "test-team",
              RegisteredUserId("test-user@test.domain")
            )
          )
          _ <- db.run(
            Tables.slackTeams += slackTeamEncrypted
          )
          response <- slackSlashCommandController.process(cryptoAlertCommand)
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
        withFakeSlackSignatureHeaders(
          FakeRequest(POST, "/").withBody(deleteChannelRequestBody)
        )
      val result = eventsController.eventsAPI().apply(fakeRequest)
      status(result) mustEqual OK

      def dbContentsFuture = db.run(Tables.slackChatHooks.result)

      eventually {
        dbContentsFuture.futureValue should matchPattern {
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

    "return unauthorised status when no signature headers are supplied" in new TestFixtures {
      val fakeRequest =
        FakeRequest(POST, "/").withBody(deleteChannelRequestBody)
      val result = eventsController.eventsAPI().apply(fakeRequest)
      status(result) mustEqual UNAUTHORIZED
    }

    "return unauthorised status when invalid signature headers are supplied" in new TestFixtures {
      override def setSignatureVerifierExpectations() =
        signatureVerifierExpectations.returning(
          Failure(new Exception("Invalid signature"))
        )

      val fakeRequest =
        withFakeSlackSignatureHeaders(
          FakeRequest(POST, "/").withBody(deleteChannelRequestBody)
        )
      val result = eventsController.eventsAPI().apply(fakeRequest)
      status(result) mustEqual UNAUTHORIZED
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
        with HooksManagerActorSlackChatFixtures
        with SlickSlashCommandFixtures
        with DatabaseInitializer
        with SlackSignatureVerifierFixtures
        with SlackManagerFixtures
        with DefaultBodyParserFixtures
        with SlackSlashCommandControllerFixtures {

      override def privateChannel: Boolean = false

      (mockPeerGroup
        .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
        .expects(*)
        .anyNumberOfTimes()

      def initialiseHook: Future[Unit] = {
        for {
          _ <- hooksManager.register(hook)
          _ <- hooksManager.start(hook.channel)
          r <- Future.successful(expectNoMessage())
        } yield r
      }

      def stopHook: Future[Unit] = {
        for {
          _ <- hooksManager.stop(hook.channel)
        } yield ()
      }

      def initialiseTeam: Future[Int] = {
        for {
          encrypted <- encryptionManager.encrypt(token1.value.getBytes)
          result <- db.run(
            Tables.slackTeams += SlackTeamEncrypted(
              slashCommandTeamId,
              SlackUserId("test-user"),
              SlackBotId("test-bot"),
              encrypted,
              "test-team",
              RegisteredUserId("test-user@test.domain")
            )
          )
        } yield result
      }

      def noop: Future[Unit] = Future.successful(())

      implicit class SeqExtension[A](s: Seq[A]) {
        def foldLeftToFuture[B](initial: B)(f: (B, A) => Future[B]): Future[B] =
          s.foldLeft(Future(initial))((future, item) =>
            future.flatMap(f(_, item))
          )

        def mapInSeries[B](f: A => Future[B]): Future[Seq[B]] =
          s.foldLeftToFuture(Seq[B]())((seq, item) => f(item).map(seq :+ _))
      }

      def slashCommands[T](
          initialise: => Future[T]
      )(
          cleanUp: => Future[Unit]
      )(
          requests: Seq[FakeRequest[AnyContentAsFormUrlEncoded]]
      ): Future[Seq[Result]] = {
        afterDbInit {
          for {
            _ <- initialise
            result <-
              requests.mapInSeries(
                call(slackSlashCommandController.slashCommand, _)
              )
            _ <- cleanUp
          } yield result
        }
      }

      def sendSlashCommandsThenStopHook(
          requests: Seq[FakeRequest[AnyContentAsFormUrlEncoded]]
      ): Future[Result] =
        slashCommands(initialiseTeam)(stopHook)(requests) map { _.last }

      def slashCommand[T](initialise: => Future[T])(cleanUp: => Future[Unit])(
          command: FakeRequest[AnyContentAsFormUrlEncoded]
      ): Future[Result] =
        slashCommands(initialise)(cleanUp)(Array(command)) map { _.head }

      def cryptoAlert(amount: Int): Future[Result] =
        slashCommand(initialiseTeam)(stopHook) {
          slashCommand("/crypto-alert", amount.toString)
        }

      def slashCommandWithTeam(
          command: FakeRequest[AnyContentAsFormUrlEncoded]
      ): Future[Result] =
        slashCommand(initialiseTeam)(noop)(command)

      def slashCommandWithStartedHook(
          request: FakeRequest[AnyContentAsFormUrlEncoded]
      ): Future[Result] =
        slashCommand {
          for {
            _ <- initialiseTeam
            r <- initialiseHook
          } yield r
        }(noop)(request)

      def submitCommand(cleanup: => Future[Unit])(
          command: SlashCommand
      ): Future[(Result, Seq[SlackChatHookEncrypted])] = {
        afterDbInit {
          for {
            _ <- initialiseTeam
            response <- slackSlashCommandController.process(command)
            dbContents <- db.run(Tables.slackChatHooks.result)
            _ <- cleanup
          } yield (response, dbContents)
        }
      }
    }

    trait TestFixturesWithPrivateChannel extends TestFixtures {
      override def privateChannel: Boolean = true
    }

    "start a chat hook specifying BTC" in new TestFixtures {

      val futureValue = submitCommand(stopHook)(cryptoAlertCommand).futureValue

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

      val futureValue = submitCommand(stopHook)(cryptoAlertCommand).futureValue

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

      val response = slackSlashCommandController.process(cryptoAlertCommand)

      contentAsString(
        response
      ) mustEqual messagesApi(
        SlackSlashCommandController.MESSAGE_CURRENCY_ERROR
      )
    }

    "return http status 200 when receiving an ssl_check due to url change" in new TestFixtures {
      val fakeRequest =
        FakeRequest(POST, "/")
          .withFormUrlEncodedBody(("ssl_check", "1"))
          .withHeaders(ArraySeq.unsafeWrapArray(fakeSlackSignatureHeaders): _*)
      val result = call(slackSlashCommandController.slashCommand, fakeRequest)
      status(result) mustEqual OK
    }

    "return correct message when issuing a valid /crypto-alert command" in new TestFixtures {
      val result = cryptoAlert(5)
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_CRYPTO_ALERT_NEW
    }

    "return reconfigure message when reconfiguring alerts" in new TestFixtures {
      val result = sendSlashCommandsThenStopHook {
        Array(
          slashCommand("/crypto-alert", "5"),
          slashCommand("/crypto-alert", "10")
        )
      }
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_CRYPTO_ALERT_RECONFIG
    }

    "return error message when not supplying amount to /crypto-alert" in new TestFixtures {
      val result = slashCommandWithTeam {
        slashCommand("/crypto-alert")
      }
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_GENERAL_ERROR
    }

    "return correct message when asking for help with /crypto-alert" in new TestFixtures {
      val result = slashCommandWithTeam {
        slashCommand("/crypto-alert", "help")
      }
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_CRYPTO_ALERT_HELP
    }

    "return correct message when pausing alerts" in new TestFixtures {
      val result = slashCommandWithStartedHook {
        slashCommand("/pause-alerts")
      }
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_PAUSE_ALERTS
    }

    "return error message when configuring alerts in private channel without membership" in new TestFixturesWithPrivateChannel {
      val result = slashCommandWithTeam {
        slashCommand("/crypto-alert", "10")
      }
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_CRYPTO_ALERT_BOT_NOT_IN_CHANNEL
    }

    "return error message when pausing alerts when there are no alerts active" in new TestFixtures {
      val result = slashCommandWithTeam {
        slashCommand("/pause-alerts")
      }
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_PAUSE_ALERTS_ERROR
    }

    "return correct message when asking for help with /pause-alerts" in new TestFixtures {
      val result = slashCommandWithTeam {
        slashCommand("/pause-alerts", "help")
      }
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_PAUSE_ALERTS_HELP
    }

    "return correct message when resuming alerts" in new TestFixtures {
      val result = sendSlashCommandsThenStopHook {
        Array(
          slashCommand("/crypto-alert", "5"),
          slashCommand("/pause-alerts"),
          slashCommand("/resume-alerts")
        )
      }
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_RESUME_ALERTS
    }

    "return error message when resuming alerts when alerts are already active" in new TestFixtures {
      val result = sendSlashCommandsThenStopHook {
        Array(
          slashCommand("/crypto-alert", "5"),
          slashCommand("/resume-alerts")
        )
      }
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_RESUME_ALERTS_ERROR
    }

    "return error message when resuming alerts when there are no alerts configured in the channel" in new TestFixtures {
      val result =
        call(
          slackSlashCommandController.slashCommand,
          slashCommandWithBadChannel("/resume-alerts")
        )
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_RESUME_ALERTS_ERROR_NOT_CONFIGURED
    }

    "return correct message when asking for help with /resume-alerts" in new TestFixtures {
      val result = slashCommandWithTeam {
        slashCommand("/resume-alerts", "help")
      }
      status(result) mustEqual OK
      contentAsString(
        result
      ) mustEqual SlackSlashCommandController.MESSAGE_RESUME_ALERTS_HELP
    }

    "return an error message when no Slack signature is supplied" in new TestFixtures {
      val result = slashCommandWithTeam {
        fakeRequestValidNoSignature("/pause-alerts")
      }
      status(result) mustEqual UNAUTHORIZED
    }

    "return an error message when an invalid Slack signature is supplied" in new TestFixtures {

      override def setSignatureVerifierExpectations() =
        signatureVerifierExpectations.returning(
          Failure(new Exception("Invalid signature"))
        )

      val result = slashCommandWithTeam {
        slashCommand("/pause-alerts")
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
        with SlackChatHookFixtures
        with SlickSlashCommandFixtures
        with SlackManagerFixtures
        with SlackChatHookDaoFixtures
        with SlickSlackTeamDaoFixtures
        with SlickSlackTeamFixtures
        with DatabaseInitializer
        with SlackSignatureVerifierFixtures {

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

      override def privateChannel: Boolean = false

      (mockPeerGroup
        .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
        .expects(*)
        .anyNumberOfTimes()
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
        .returning(Future {
          ValidSecret(RegisteredUserId(user))
        })

      (mockSlackSecretsManagerService.unbind _)
        .expects(RegisteredUserId(user))
        .once()
        .returning(Future {
          Unbind(RegisteredUserId(user))
        })

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
        .returning(Future {
          slackTeam
        })

      (mockSlackSecretsManagerService.verifySecret _)
        .expects(*, *)
        .once()
        .returning(Future {
          ValidSecret(RegisteredUserId(user))
        })

      (mockSlackSecretsManagerService.unbind _)
        .expects(RegisteredUserId(user))
        .once()
        .returning(Future {
          Unbind(RegisteredUserId(user))
        })

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
        with DefaultBodyParserFixtures
        with Auth0ActionFixtures {

      def mockAuth0Action: Auth0ValidateJWTAction = mockAuth0ActionAlwaysSuccess

      (mockSlackSecretsManagerService.generateSecret _)
        .expects(*)
        .returning(Future {
          slackAuthSecret
        })
        .anyNumberOfTimes()

      val controller =
        new Auth0Controller(
          mockAuth0Action,
          mockSlackSecretsManagerService,
          Helpers.stubControllerComponents(),
          config
        )

      val testUser = Some("test-user")

      (mockPeerGroup.start _).expects().once()
      (mockPeerGroup.setMaxConnections _).expects(*).once()
      (mockPeerGroup.addPeerDiscovery _).expects(*).once()
      (mockPeerGroup
        .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
        .expects(*)
        .anyNumberOfTimes()
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
