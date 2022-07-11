package unittests

import actors.{AuthenticationActor, EncryptionActor, HooksManagerActorSlackChat, HooksManagerActorWeb, MemPoolWatcherActor, Register, Registered, Start, Started, Stop, Stopped, TxFilterActor, TxMessagingActorSlackChat, TxMessagingActorWeb, TxPersistenceActor, TxUpdate, Update, Updated}
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
import play.api.libs.json.{JsArray, Json}
import play.api.{Configuration, Logging, inject}
import services.{HooksManagerSlackChat, HooksManagerWeb, MemPoolWatcher, MemPoolWatcherService, PeerGroupSelection, SodiumEncryptionManager, User, UserManagerService}
import slick.BtcPostgresProfile.api._
import slick.dbio.{DBIO, Effect}
import slick.jdbc.JdbcBackend.Database
import slick.lifted.TableQuery
import slick.sql.{FixedSqlAction, FixedSqlStreamingAction}
import slick.{DatabaseExecutionContext, Tables, jdbc}

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
        sender() ! Failure(new IllegalArgumentException("unrecognized message format"))
    }
  }

  trait HookManagerMock[X, Y] {
    def start(uri: X): Unit
    def register(hook: Y): Unit
    def stop(uri: X): Unit
    def update(hook: Y): Unit
  }

  trait WebhookManagerMock extends HookManagerMock[URI, Webhook]
  trait SlackChatManagerMock extends HookManagerMock[SlackChannel, SlackChatHook]

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
      case x =>
        Failure(new IllegalArgumentException("unrecognized message: $x"))
    }
  }

  object MockWebhookManagerActor {
    def props(mock: WebhookManagerMock) = Props(new MockWebhookManagerActor(mock))
  }
  class MockWebhookManagerActor(val mock: WebhookManagerMock)
    extends MockHookManagerActor[URI, Webhook]

  object MockSlackChatManagerActor {
    def props(mock: SlackChatManagerMock) = Props(new MockSlackChatManagerActor(mock))
  }
  class MockSlackChatManagerActor(val mock: SlackChatManagerMock)
    extends MockHookManagerActor[SlackChannel, SlackChatHook]

//    Actor {
//    val hooks = mutable.Map[URI, Webhook]()
//
//    override def receive: Receive = {
//      case Start(uri: URI) =>
//        mock.start(uri)
//        sender() ! Success(Started(hooks(uri)))
//      case Register(hook: Webhook) =>
//        mock.register(hook)
//        hooks(hook.uri) = hook
//        sender() ! Success(Registered(hook))
//      case Stop(uri: URI) =>
//        mock.stop(uri)
//        sender() ! Success(Stopped(hooks(uri)))
//      case x =>
//        Failure(new IllegalArgumentException("unrecognized message: $x"))
//    }
//  }

  trait MemPoolWatcherActorFixtures {
    val mainNetParams: MainNetParams
    val mockPeerGroup: PeerGroup
    val injector: Injector
    val actorSystem: ActorSystem
    val executionContext: ExecutionContext

    val pgs = new PeerGroupSelection() {
      val params = mainNetParams
      lazy val get = mockPeerGroup
    }
    val memPoolWatcherActor = actorSystem.actorOf(MemPoolWatcherActor.props(pgs, injector.instanceOf[DatabaseExecutionContext]))
    val memPoolWatcher = new MemPoolWatcher(memPoolWatcherActor)(actorSystem, executionContext)
  }

  trait TransactionFixtures {
    val mainNetParams: MainNetParams
    lazy val transactions = Json.parse(Source.fromResource("tx_valid.json").getLines().mkString)
      .as[Array[JsArray]].map(_.value).filter(_.size > 1)
      .map(testData => mainNetParams.getDefaultSerializer.makeTransaction(HEX.decode(testData(1).as[String].toLowerCase)))
  }

  trait WebSocketFixtures extends MockFactory {
    val actorSystem: ActorSystem
    val mockWs = mock[WebSocketMock]
    val mockWsActor = actorSystem.actorOf(MockWebsocketActor.props(mockWs))
  }

  trait MemPoolWatcherFixtures extends MockFactory {
    val mainNetParams = MainNetParams.get()

    class MainNetPeerGroup extends PeerGroup(mainNetParams)

    val mockMemPoolWatcher = mock[MemPoolWatcherService]
    val mockPeerGroup = mock[MainNetPeerGroup]

    def memPoolWatcherExpectations(ch: CallHandler1[ActorRef, Unit]): ch.Derived = {
      ch.never()
    }

    memPoolWatcherExpectations((mockMemPoolWatcher.addListener _).expects(*))
  }

  trait ConfigurationFixtures {
    val config = Configuration(ConfigFactory.load("application.test.conf"))
  }

  trait ActorGuiceFixtures extends ConfigurationFixtures {
    val mockMemPoolWatcher: MemPoolWatcherService
    val actorSystem: ActorSystem
    val bindModule: GuiceableModule

    def builder = new GuiceInjectorBuilder()
      .bindings(bindModule)
      .overrides(inject.bind(classOf[Configuration]).toInstance(config))
      .overrides(inject.bind(classOf[ActorSystem]).toInstance(actorSystem))
      .overrides(inject.bind(classOf[MemPoolWatcherService]).toInstance(mockMemPoolWatcher))

    val injector = builder.build()
  }

  trait DatabaseGuiceFixtures {
    val actorSystem: ActorSystem
    val db: Database
    val executionContext: ExecutionContext

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
    val hook = Webhook(key, threshold = 100L, isRunning = true)
    val stoppedHook = hook.copy(isRunning = false)
    val newHook = Webhook(key, threshold = 200L, isRunning = true)
  }

  trait SlackChatHookFixtures {
    val key = SlackChannel("#test")
    val hook = SlackChatHook(key, threshold = 100L, isRunning = true, token = "test_token_1")
    val stoppedHook = hook.copy(isRunning = false)
    val newHook = SlackChatHook(key, threshold = 200L, isRunning = true, token = "test_token_2")
  }

  trait DatabaseInitializer {
    val db: Database
    implicit val executionContext: ExecutionContext

    def afterDbInit[T](fn: => Future[T]): Future[T] = {
      for {
        _ <- db.run(DBIO.seq(Tables.schema.dropIfExists, Tables.schema.create))
        response <- fn
      } yield response
    }
  }

  trait HookDaoTestLogic[X, Y <: Hook[X]] extends DatabaseInitializer {
    val hook: Y
    val newHook: Y
    val key: X
    val hookDao: HookDao[X, Y]
    val tableQuery: TableQuery[_] //= Tables.webhooks

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
    override val tableQuery = Tables.webhooks
  }

  trait SlackChatDaoTestLogic extends HookDaoTestLogic[SlackChannel, SlackChatHook] {
    override val tableQuery = Tables.slackChatHooks
  }

  trait SlackChatHookDaoFixtures {
    val injector: Injector
    val hookDao = injector.instanceOf[SlackChatHookDao]
  }

  trait WebhookDaoFixtures {
    val injector: Injector
    val hookDao = injector.instanceOf[WebhookDao]
  }

  trait WebhookActorFixtures {
    val actorSystem: ActorSystem
    val injector: Injector
    val key: URI
    val hook: Webhook
    val newHook: Webhook
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
    val actorSystem: ActorSystem
    val injector: Injector

    val hooksActor = {
      actorSystem.actorOf(
        HooksManagerActorSlackChat.props(
          injector.instanceOf[TxMessagingActorSlackChat.Factory],
          injector.instanceOf[TxFilterActor.Factory],
          injector.instanceOf[SlackChatHookDao],
          injector.instanceOf[DatabaseExecutionContext]
        )
      )
    }

    val insertHook = Tables.slackChatHooks += hook
    val queryHooks = Tables.slackChatHooks.result
  }

  trait SlickSlackTeamDaoFixtures {
    val injector: Injector
    val slickSlackTeamDao = injector.instanceOf[SlickSlackTeamDao]
  }

  trait SlickSlackTeamFixtures {
    val userId = "testUser"
    val botId = "testBotId"
    val accessToken = "testToken"
    val teamId = "testTeamId"
    val teamName = "testTeam"
    val slackTeam = SlackTeam(teamId, userId, botId, accessToken, teamName)
  }

  trait SlickSlashCommandFixtures {
    val channelId = "1234"
    val command = "/test"
    val text = ""
    val teamDomain = None
    val teamId = "5678"
    val channelName = Some("test-channel")
    val userId = Some("91011")
    val userName = Some("test-user")
    val isEnterpriseInstall = Some(false)
    val timeStamp = Some(java.time.LocalDateTime.of(2001, 1, 1, 0, 0))
    val slashCommand = SlashCommand(None, channelId, command, text, teamDomain, teamId, channelName, userId,
      userName, isEnterpriseInstall, timeStamp)
    val messagesApi = new DefaultMessagesApi(
      Map("en" -> Map("slackResponse.currencyError" -> "I currently only provide alerts for BTC, but other currencies are coming soon."))
    )
  }

  trait SlickSlashCommandHistoryDaoFixtures {
    val injector: Injector
    val slickSlashCommandHistoryDao = injector.instanceOf[SlickSlashCommandHistoryDao]
  }

  trait SlickTransactionUpdateDaoFixtures {
    val injector: Injector
    val slickTransactionUpdateDao = injector.instanceOf[SlickTransactionUpdateDao]
  }

  trait TxUpdateFixtures {
    val timeStamp = java.time.LocalDateTime.of(2001, 1, 1, 0, 0)
    val tx = TxUpdate("testHash", 10, timeStamp, isPending = true, List(), List())
  }

  trait HookActorTestLogic[X, Y <: Hook[X]] extends DatabaseInitializer {
    implicit val timeout: Timeout
    val hooksActor: ActorRef
    val hook: Y
    val newHook: Y
    val key: Any
    val insertHook: FixedSqlAction[Int, NoStream, Effect.Write]
    val queryHooks: FixedSqlStreamingAction[Seq[Y], Y, Effect.Read]

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
          stopped <- hooksActor ? Stop(key)
          _ <- Future {
            wait(500.millis) // Wait for child actors to die
          }
          restarted <- hooksActor ? Start(key)
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
          registered <- hooksActor ? Register(hook)
          started <- hooksActor ? Start(key)
          error <- hooksActor ? Start(key)
        } yield error
      }
    }

  }

  trait UserFixtures extends MockFactory {
    val mockUser = mock[User]
    val mockUserManager = mock[UserManagerService]
  }

  trait TxPersistenceActorFixtures extends MockFactory {
    val actorSystem: ActorSystem
    val executionContext: ExecutionContext
    val mockMemPoolWatcher: MemPoolWatcherService = mock[MemPoolWatcherService]
    val mockSlickTransactionUpdateDao = mock[SlickTransactionUpdateDao]
    val txPersistenceActor = actorSystem.actorOf(TxPersistenceActor.props(mockSlickTransactionUpdateDao, mockMemPoolWatcher, executionContext))
  }

  trait TxWatchActorFixtures {
    val actorSystem: ActorSystem
    val mockWsActor: ActorRef
    val mockMemPoolWatcher: MemPoolWatcherService
    val mockUserManager: UserManagerService

    val txWatchActor =
      actorSystem.actorOf(AuthenticationActor.props(mockWsActor, mockMemPoolWatcher, mockUserManager)(actorSystem))
  }

  trait SlackChatHookManagerFixtures extends MockFactory {
    val actorSystem: ActorSystem
    val hookDao: SlackChatHookDao
    val executionContext: ExecutionContext

    val slackChatManagerMock = mock[SlackChatManagerMock]
    val mockSlackChatManagerActor = actorSystem.actorOf(MockSlackChatManagerActor.props(slackChatManagerMock))
    val slackChatManager =
      new HooksManagerSlackChat(hookDao, actor = mockSlackChatManagerActor)(actorSystem, executionContext)
  }

  trait WebhookManagerFixtures extends MockFactory {
    val actorSystem: ActorSystem
    val hookDao: WebhookDao
    val executionContext: ExecutionContext
    val webhookManagerMock = mock[WebhookManagerMock]
    val mockWebhookManagerActor = actorSystem.actorOf(MockWebhookManagerActor.props(webhookManagerMock))
    val webhooksManager = new HooksManagerWeb(hookDao, actor = mockWebhookManagerActor)(actorSystem, executionContext)
  }

  trait EncryptionManagerFixtures {
    val executionContext: ExecutionContext
    val actorSystem: ActorSystem
    val config: Configuration
    val encryptionActor: ActorRef
    val encryptionManager = new SodiumEncryptionManager(encryptionActor, config)(actorSystem, executionContext)
  }

  trait EncryptionActorFixtures {
    val actorSystem: ActorSystem
    val secret: Array[Byte]  =
      Array(56, -5, 127, -79, -126, 3, 110, 29, -57, 55, -97, 79, -32, -126, 83, -74, 66, 119, -35, -65, 75,
        -69, -93, -11, 80, -55, 105, -22, 95, 76, 59, 37)
    val encryptionActor = actorSystem.actorOf(EncryptionActor.props())
    val plainText = "To be or not to be!"
    val plainTextBinary = plainText.getBytes
    val secondPlainText = "That is the question!"
    val secondPlainTextBinary = secondPlainText.getBytes
  }

  trait WebhooksActorFixtures {
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
