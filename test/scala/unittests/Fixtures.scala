package unittests

import actors.EncryptionActor.Encrypted
import actors.{
  AuthenticationActor,
  EncryptionActor,
  HooksManagerActorSlackChat,
  HooksManagerActorWeb,
  MemPoolWatcherActor,
  Register,
  Registered,
  SlackSecretsActor,
  Start,
  Started,
  Stop,
  Stopped,
  TxFilterActor,
  TxMessagingActorSlackChat,
  TxMessagingActorWeb,
  TxPersistenceActor,
  TxUpdate,
  Update,
  Updated
}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.util.Timeout
import com.google.common.util.concurrent.ListenableFuture
import com.google.inject.AbstractModule
import com.typesafe.config.ConfigFactory
import dao._
import org.bitcoinj.core.PeerGroup
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.params.MainNetParams
import org.scalamock.handlers.CallHandler1
import org.scalamock.scalatest.MockFactory
import org.scalamock.util.Defaultable
import play.api.i18n.DefaultMessagesApi
import play.api.inject.Injector
import play.api.inject.guice.{GuiceInjectorBuilder, GuiceableModule}
import play.api.libs.json.{JsArray, JsValue, Json}
import play.api.test.FakeRequest
import play.api.test.Helpers.POST
import play.api.{Configuration, Logging, inject}
import services.{
  HooksManagerSlackChat,
  HooksManagerWeb,
  MailManager,
  MemPoolWatcher,
  MemPoolWatcherService,
  PeerGroupSelection,
  SlackManager,
  SlackSecretsManagerService,
  SodiumEncryptionManager,
  User,
  UserManagerService
}
import slick.BtcPostgresProfile.api._
import slick.dbio.{DBIO, Effect}
import slick.jdbc.JdbcBackend.Database
import slick.lifted.TableQuery
import slick.sql.{FixedSqlAction, FixedSqlStreamingAction}
import slick.{
  DatabaseExecutionContext,
  EncryptionExecutionContext,
  SlackClientExecutionContext,
  Tables,
  jdbc
}

import java.net.URI
import javax.inject.Provider
import scala.collection.mutable
import scala.concurrent.duration.{DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.util.{Failure, Success}

//noinspection TypeAnnotation
// scalafix:off
object Fixtures {

  implicit val d = new Defaultable[ListenableFuture[_]] {
    override val default = null
  }

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
        sender() ! Failure(
          new IllegalArgumentException("unrecognized message format")
        )
    }
  }

  trait HookManagerMock[X, Y] {
    def start(uri: X): Unit
    def register(hook: Y): Unit
    def stop(uri: X): Unit
    def update(hook: Y): Unit
  }

  trait WebhookManagerMock extends HookManagerMock[URI, Webhook]
  trait SlackChatManagerMock
      extends HookManagerMock[SlackChannelId, SlackChatHook]

  trait MockHookManagerActor[X, Y <: Hook[X]] extends Actor with Logging {
    val mock: HookManagerMock[X, Y]
    val hooks = mutable.Map[X, Y]()

    override def receive: Receive = {
      case Start(uri: X) =>
        logger.debug("Received start request for $uri")
        mock.start(uri)
        sender() ! Success(Started(hooks(uri)))
      case Register(hook: Y) =>
        mock.register(hook)
        hooks(hook.key) = hook
        sender() ! Success(Registered(hook))
      case Stop(uri: X) =>
        mock.stop(uri)
        sender() ! Success(Stopped(hooks(uri)))
      case Update(hook: Y) =>
        mock.update(hook)
        sender() ! Success(Updated(hook))
      case _ =>
        Failure(new IllegalArgumentException("unrecognized message: $x"))
    }
  }

  object MockWebhookManagerActor {
    def props(mock: WebhookManagerMock) = Props(
      new MockWebhookManagerActor(mock)
    )
  }
  class MockWebhookManagerActor(val mock: WebhookManagerMock)
      extends MockHookManagerActor[URI, Webhook]

  object MockSlackChatManagerActor {
    def props(mock: SlackChatManagerMock) = Props(
      new MockSlackChatManagerActor(mock)
    )
  }

  class MockSlackChatManagerActor(val mock: SlackChatManagerMock)
      extends MockHookManagerActor[SlackChannelId, SlackChatHook]

  trait ProvidesInjector {
    val injector: Injector
  }

  trait HasActorSystem {
    val actorSystem: ActorSystem
  }

  trait HasDatabase {
    val db: Database
  }

  trait HasExecutionContext {
    val executionContext: ExecutionContext
  }

  trait HasBindModule {
    val bindModule: GuiceableModule
  }

  trait ProvidesTestBindings
      extends HasBindModule
      with HasExecutionContext
      with HasActorSystem
      with HasDatabase

  trait MemPoolWatcherActorFixtures {
    env: MemPoolWatcherFixtures
      with ActorGuiceFixtures
      with HasActorSystem
      with HasExecutionContext =>

    val executionContext: ExecutionContext

    val pgs = new PeerGroupSelection() {
      val params = mainNetParams
      lazy val get = mockPeerGroup
    }
    val memPoolWatcherActor = actorSystem.actorOf(
      MemPoolWatcherActor.props(
        pgs,
        injector.instanceOf[DatabaseExecutionContext]
      )
    )
    val memPoolWatcher =
      new MemPoolWatcher(memPoolWatcherActor)(actorSystem, executionContext) {
        override def initialiseFuture(): Future[Unit] = {
          Future { () }
        }
      }
  }

  trait TransactionFixtures { env: MemPoolWatcherFixtures =>
    lazy val transactions = Json
      .parse(Source.fromResource("tx_valid.json").getLines().mkString)
      .as[Array[JsArray]]
      .map(_.value)
      .filter(_.size > 1)
      .map(testData =>
        mainNetParams.getDefaultSerializer
          .makeTransaction(HEX.decode(testData(1).as[String].toLowerCase))
      )
  }

  trait WebSocketFixtures extends MockFactory { env: HasActorSystem =>
    val actorSystem: ActorSystem
    val mockWs = mock[WebSocketMock]
    val mockWsActor = actorSystem.actorOf(MockWebsocketActor.props(mockWs))
  }

//  trait MainNetParamsFixtures {
//    val mainNetParams = MainNetParams.get()
//  }

  trait MemPoolWatcherFixtures extends MockFactory {

    val mainNetParams = MainNetParams.get()
    class MainNetPeerGroup extends PeerGroup(mainNetParams)

    val mockMemPoolWatcher = mock[MemPoolWatcherService]
    val mockPeerGroup = mock[MainNetPeerGroup]

    def memPoolWatcherExpectations(
        ch: CallHandler1[ActorRef, Unit]
    ): ch.Derived = {
      ch.never()
    }

    memPoolWatcherExpectations((mockMemPoolWatcher.addListener _).expects(*))
  }

  trait ConfigurationFixtures {
    val config = Configuration(ConfigFactory.load("application.test.conf"))
  }

  trait ActorGuiceFixtures extends ProvidesInjector {
    env: ConfigurationFixtures
      with MemPoolWatcherFixtures
      with HasActorSystem
      with HasBindModule =>

    def builder = new GuiceInjectorBuilder()
      .bindings(bindModule)
      .overrides(inject.bind(classOf[Configuration]).toInstance(config))
      .overrides(inject.bind(classOf[ActorSystem]).toInstance(actorSystem))
      .overrides(
        inject
          .bind(classOf[MemPoolWatcherService])
          .toInstance(mockMemPoolWatcher)
      )

    val injector = builder.build()
  }

  trait DatabaseGuiceFixtures extends ProvidesInjector {
    env: HasActorSystem with HasDatabase with HasExecutionContext =>

    class DatabaseTestModule extends AbstractModule {
      override def configure(): Unit = {
        bind(classOf[Database]).toProvider(new Provider[Database] {
          val get: jdbc.JdbcBackend.Database = db
        })
        bind(classOf[ExecutionContext]).toInstance(executionContext)
      }
    }

    def builder = new GuiceInjectorBuilder()
      .bindings(new DatabaseTestModule)
      .overrides(inject.bind(classOf[ActorSystem]).toInstance(actorSystem))

    val injector = builder.build()
  }

  trait WebhookFixtures {
    val key = new URI("http://test")
    val originalThreshold = 100L
    val newThreshold = 200L
    val hook = Webhook(key, threshold = originalThreshold, isRunning = true)
    val stoppedHook = hook.copy(isRunning = false)
    val newHook = Webhook(key, threshold = newThreshold, isRunning = true)
  }

  trait SlackChatHookFixtures {
    val encryptedToken1 =
      Encrypted(cipherText = Array[Byte] { 1 }, nonce = Array[Byte] { 1 })
    val encryptedToken2 =
      Encrypted(cipherText = Array[Byte] { 2 }, nonce = Array[Byte] { 2 })
    val originalThreshold = 100L
    val newThreshold = 200L
    val key = SlackChannelId("#test")
    val hook = SlackChatHook(
      key,
      threshold = originalThreshold,
      isRunning = true,
      token = "test_token_1"
    )
    val stoppedHook = hook.copy(isRunning = false)
    val newHook = SlackChatHook(
      key,
      threshold = newThreshold,
      isRunning = true,
      token = "test_token_2"
    )
    val encryptedHook =
      SlackChatHookEncrypted(
        key,
        threshold = newThreshold,
        isRunning = true,
        token = encryptedToken1
      )
  }

  trait DatabaseInitializer { env: HasDatabase with HasExecutionContext =>
    val db: Database
    implicit val ec = executionContext

    def afterDbInit[T](fn: => Future[T]): Future[T] = {
      for {
        _ <- db.run(DBIO.seq(Tables.schema.dropIfExists, Tables.schema.create))
        response <- fn
      } yield response
    }
  }

  trait HookDaoTestLogic[X, Y <: Hook[X]] extends DatabaseInitializer {
    env: HasDatabase with HasExecutionContext =>
    val hook: Y
    val newHook: Y
    val key: X
    val hookDao: HookDao[X, Y]
    val tableQuery: TableQuery[_] // = Tables.webhooks

    def insertHook() = {
      afterDbInit {
        for {
          n <- hookDao.insert(hook)
          queryResult <- db.run(tableQuery.result)
        } yield (n, queryResult)
      }
    }

    def findNonExistentHook() = {
      afterDbInit {
        for {
          hook <- hookDao.find(key)
        } yield hook
      }
    }

    def findHook() = {
      afterDbInit {
        for {
          n <- hookDao.insert(hook)
          hook <- hookDao.find(key)
        } yield (n, hook)
      }
    }

    def updateHook() = {
      afterDbInit {
        for {
          i <- hookDao.insert(hook)
          j <- hookDao.update(newHook)
          queryResult <- db.run(tableQuery.result)
        } yield (i, j, queryResult)
      }
    }
  }

  trait WebhookDaoTestLogic extends HookDaoTestLogic[URI, Webhook] {
    env: HasDatabase with HasExecutionContext =>
    override val tableQuery = Tables.webhooks
  }

  trait SlackChatDaoTestLogic
      extends HookDaoTestLogic[SlackChannelId, SlackChatHook] {
    env: HasDatabase with HasExecutionContext =>

    override val tableQuery = Tables.slackChatHooks
  }

  trait SlackChatHookDaoFixtures {
    env: EncryptionManagerFixtures
      with ProvidesInjector
      with HasExecutionContext
      with HasDatabase =>

    val db: Database
    val executionContext: ExecutionContext

    val hookDao =
      new SlickSlackChatDao(
        db,
        injector.instanceOf[DatabaseExecutionContext],
        encryptionManager
      )(executionContext)
  }

  trait WebhookDaoFixtures { env: ProvidesInjector =>
    val hookDao = injector.instanceOf[WebhookDao]
  }

  trait WebhookActorFixtures { env: ProvidesInjector with HasActorSystem =>
    val actorSystem: ActorSystem
    val hook: Webhook
    val hooksActor = {
      actorSystem.actorOf(
        HooksManagerActorWeb.props(
          injector.instanceOf[TxMessagingActorWeb.Factory],
          injector.instanceOf[TxFilterActor.Factory],
          injector.instanceOf[WebhookDao],
          injector.instanceOf[DatabaseExecutionContext]
        )
      )
    }
    val insertHook = Tables.webhooks += hook
    val queryHooks = Tables.webhooks.result
  }

  trait SlackChatActorFixtures extends SlackChatHookFixtures {
    env: HasActorSystem with ProvidesInjector =>
    val actorSystem: ActorSystem
    val hookDao: SlackChatHookDao

    val hooksActor = {
      actorSystem.actorOf(
        HooksManagerActorSlackChat.props(
          injector.instanceOf[TxMessagingActorSlackChat.Factory],
          injector.instanceOf[TxFilterActor.Factory],
          hookDao,
          injector.instanceOf[DatabaseExecutionContext]
        )
      )
    }

    val insertHook = Tables.slackChatHooks += encryptedHook
    val queryHooks = Tables.slackChatHooks.result
  }

  trait SlickSlackTeamDaoFixtures {
    env: HasDatabase with ProvidesInjector with EncryptionManagerFixtures =>
    val db: Database
    val slickSlackTeamDao = new SlickSlackTeamDao(
      db,
      injector.instanceOf[DatabaseExecutionContext],
      encryptionManager
    )
  }

  trait SlickSlackTeamFixtures {
    val teamUserId = SlackUserId("testUser")
    val botId = SlackBotId("testBotId")
    val accessToken = "testToken"
    val teamId = SlackTeamId("testTeamId")
    val teamName = "testTeam"
    val registeredUserId = RegisteredUserId("testUser@test.domain")
    val slackTeam = SlackTeam(teamId, teamUserId, botId, accessToken, teamName, registeredUserId)
    val updatedSlackTeam = slackTeam.copy(teamName = "updated")
  }

  trait SlickSlashCommandFixtures {
    val channelId = SlackChannelId("1234")
    val command = "/test"
    val text = ""
    val teamDomain = None
    val slashCommandTeamId = SlackTeamId("5678")
    val channelName = Some("test-channel")
    val userId = Some(SlackUserId("91011"))
    val userName = Some("test-user")
    val testToken = "test-token"
    val isEnterpriseInstall = Some(false)
    val timeStamp = Some(java.time.LocalDateTime.of(2001, 1, 1, 0, 0))
    val slashCommand = SlashCommand(
      None,
      channelId,
      command,
      text,
      teamDomain,
      slashCommandTeamId,
      channelName,
      userId,
      userName,
      isEnterpriseInstall,
      timeStamp
    )
    val messagesApi = new DefaultMessagesApi(
      Map(
        "en" -> Map(
          "slackResponse.currencyError" -> "I currently only provide alerts for BTC, but other currencies are coming soon."
        )
      )
    )
    val cryptoAlertCommand =
      SlashCommand(
        None,
        channelId,
        "/crypto-alert",
        "5 BTC",
        teamDomain,
        slashCommandTeamId,
        channelName,
        userId,
        userName,
        isEnterpriseInstall,
        None
      )

    def fakeRequestValid(command: String, amount: String) =
      FakeRequest(POST, "/").withFormUrlEncodedBody(
        "token" -> testToken,
        "team_id" -> slashCommandTeamId.value,
        "team_domain" -> "",
        "channel_id" -> channelId.value,
        "channel_name" -> "testChannel",
        "user_id" -> "91011",
        "user_name" -> "test-user",
        "command" -> command,
        "text" -> amount,
        "is_enterprise_install" -> "false"
      )
  }

  trait SlickSlashCommandHistoryDaoFixtures { env: ProvidesInjector =>
    val injector: Injector
    val slickSlashCommandHistoryDao =
      injector.instanceOf[SlickSlashCommandHistoryDao]
  }

  trait SlickTransactionUpdateDaoFixtures { env: ProvidesInjector =>
    val injector: Injector
    val slickTransactionUpdateDao =
      injector.instanceOf[SlickTransactionUpdateDao]
  }

  trait TxUpdateFixtures {
    val timeStamp = java.time.LocalDateTime.of(2001, 1, 1, 0, 0)
    val tx =
      TxUpdate("testHash", 10, timeStamp, isPending = true, List(), List())
  }

  trait HookActorTestLogic[X, Y <: Hook[X], Z] extends DatabaseInitializer {
    env: HasDatabase with HasExecutionContext =>

    implicit val timeout: Timeout
    val hooksActor: ActorRef
    val hook: Y
    val newHook: Y
    val key: Any
    val insertHook: FixedSqlAction[Int, NoStream, Effect.Write]
    val queryHooks: FixedSqlStreamingAction[Seq[Z], Z, Effect.Read]

    def wait(duration: FiniteDuration): Unit

    def stopHook() = {
      afterDbInit {
        hooksActor ? Stop(key)
      }
    }

    def startHook() = {
      afterDbInit {
        hooksActor ? Start(key)
      }
    }

    def registerExistingHook() = {
      afterDbInit {
        for {
          _ <- db.run(insertHook)
          registered <- hooksActor ? Register(hook)
        } yield registered
      }
    }

    def registerHook() = {
      afterDbInit {
        for {
          response <- hooksActor ? Register(hook)
          dbContents <- db.run(queryHooks)
        } yield (response, dbContents)
      }
    }

    def updateHook() = {
      afterDbInit {
        for {
          _ <- db.run(insertHook)
          response <- hooksActor ? Update(newHook)
          dbContents <- db.run(queryHooks)
        } yield (response, dbContents)
      }
    }

    def registerStartStopRestartStop() = {
      afterDbInit {
        for {
          registered <- hooksActor ? Register(hook)
          started <- hooksActor ? Start(key)
          _ <- Future {
            wait(500.millis) // Wait for child actors to start
          }
          stopped <- hooksActor ? Stop(key)
          _ <- Future {
            wait(500.millis) // Wait for child actors to die
          }
          restarted <- hooksActor ? Start(key)
          _ <- Future {
            wait(500.millis) // Wait for child actors to start
          }
          finalStop <- hooksActor ? Stop(key)
          _ <- Future {
            wait(500.millis)
          } // Wait for database to become consistent
          dbContents <- db.run(queryHooks)
        } yield (registered, started, stopped, restarted, finalStop, dbContents)
      }
    }

    def registerStartStart() = {
      afterDbInit {
        for {
          _ <- hooksActor ? Register(hook)
          _ <- hooksActor ? Start(key)
          error <- hooksActor ? Start(key)
        } yield error
      }
    }

  }

  trait SecretsManagerFixtures extends MockFactory {
    val mockSlackSecretsManagerService = mock[SlackSecretsManagerService]
    val slackAuthSecret = Secret(Array(0x00, 0xff).map(_.toByte))
  }

  trait SlackManagerFixtures extends MockFactory {
    env: ConfigurationFixtures with ProvidesInjector =>
    val slackClientExecutionContext =
      injector.instanceOf[SlackClientExecutionContext]
    class MockSlackManager
        extends SlackManager(config, slackClientExecutionContext)
    val mockSlackManagerService = mock[MockSlackManager]
  }

  trait UserFixtures extends MockFactory {
    val mockUser = mock[User]
    val mockUserManager = mock[UserManagerService]
  }

  trait MockMailManagerFixtures extends MockFactory {
    val emailName = "testName"
    val emailAddress = "test@test.com"
    val feedbackMessage = "This is a test feedback message."
    val expectedValidEmailSubject = "Feedback - testName test@test.com"
    val fakeRequestFormSubmission =
      FakeRequest(POST, "/").withFormUrlEncodedBody(
        ("name", emailName),
        ("email", emailAddress),
        ("message", feedbackMessage)
      )
    val mockMailManager = mock[MailManager]
  }

  trait TxPersistenceActorFixtures extends MockFactory {
    env: HasActorSystem
      with HasExecutionContext
      with ProvidesInjector
      with MemPoolWatcherFixtures =>

    val actorSystem: ActorSystem
//    val mockMemPoolWatcher: MemPoolWatcherService
    val executionContext: ExecutionContext
    val injector: Injector
    val mockTransactionUpdateDao = mock[TransactionUpdateDao]
    val random = injector.instanceOf[scala.util.Random]
    val txPersistenceActor = actorSystem.actorOf(
      TxPersistenceActor.props(
        mockTransactionUpdateDao,
        mockMemPoolWatcher,
        random,
        executionContext
      )
    )
  }

  trait TxWatchActorFixtures {
    env: HasActorSystem
      with MemPoolWatcherFixtures
      with WebSocketFixtures
      with UserFixtures =>

    val actorSystem: ActorSystem

    val txWatchActor =
      actorSystem.actorOf(
        AuthenticationActor.props(
          mockWsActor,
          mockMemPoolWatcher,
          mockUserManager
        )(actorSystem)
      )
  }

  trait SlackChatHookManagerFixtures extends MockFactory {
    env: HasActorSystem with HasExecutionContext =>
    val actorSystem: ActorSystem
    val hookDao: SlackChatHookDao
    val executionContext: ExecutionContext

    val slackChatManagerMock = mock[SlackChatManagerMock]
    val mockSlackChatManagerActor =
      actorSystem.actorOf(MockSlackChatManagerActor.props(slackChatManagerMock))
    val slackChatManager =
      new HooksManagerSlackChat(hookDao, actor = mockSlackChatManagerActor)(
        actorSystem,
        executionContext
      )
  }

  trait WebhookManagerFixtures extends MockFactory {
    env: HasActorSystem with HasExecutionContext =>
    val actorSystem: ActorSystem
    val hookDao: WebhookDao
    val executionContext: ExecutionContext
    val webhookManagerMock = mock[WebhookManagerMock]
    val mockWebhookManagerActor =
      actorSystem.actorOf(MockWebhookManagerActor.props(webhookManagerMock))
    val webhooksManager =
      new HooksManagerWeb(hookDao, actor = mockWebhookManagerActor)(
        actorSystem,
        executionContext
      )
  }

  trait SlackEventsControllerFixtures {
    val deleteChannelRequestBody: JsValue = Json.parse("""
  {
    "event" :
    {
      "type" : "channel_deleted",
      "channel" : "1234"
    }
  }
  """)
  }

  trait EncryptionManagerFixtures {
    env: ProvidesInjector
      with ConfigurationFixtures
      with EncryptionActorFixtures =>

    val encryptionExecutionContext =
      injector.instanceOf[EncryptionExecutionContext]
    val encryptionManager =
      new SodiumEncryptionManager(
        encryptionActor,
        config,
        encryptionExecutionContext
      )
  }

  trait SlackSecretsActorFixtures {
    env: HasActorSystem with EncryptionManagerFixtures =>
    val actorSystem: ActorSystem
    val slackSecretsActor = actorSystem.actorOf(
      SlackSecretsActor.props(encryptionManager, encryptionExecutionContext)
    )
    val userId = RegisteredUserId("test-user")
    val anotherUserId = RegisteredUserId("test-user-2")
  }

  trait EncryptionActorFixtures { env: HasActorSystem =>
    val actorSystem: ActorSystem
    val secret: Array[Byte] =
      Array(56, -5, 127, -79, -126, 3, 110, 29, -57, 55, -97, 79, -32, -126, 83,
        -74, 66, 119, -35, -65, 75, -69, -93, -11, 80, -55, 105, -22, 95, 76,
        59, 37)
    val encryptionActor = actorSystem.actorOf(EncryptionActor.props())
    val plainText = "To be or not to be!"
    val plainTextBinary = plainText.getBytes
    val secondPlainText = "That is the question!"
    val secondPlainTextBinary = secondPlainText.getBytes
  }

  trait WebhooksActorFixtures { env: HasActorSystem with ProvidesInjector =>
    val actorSystem: ActorSystem
    val injector: Injector
    val hooksActor = {
      actorSystem.actorOf(
        HooksManagerActorWeb.props(
          injector.instanceOf[TxMessagingActorWeb.Factory],
          injector.instanceOf[TxFilterActor.Factory],
          injector.instanceOf[WebhookDao],
          injector.instanceOf[DatabaseExecutionContext]
        )
      )
    }
  }

}

// scalafix:on
