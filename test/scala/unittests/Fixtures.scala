package unittests

import actions.Auth0ValidateJWTAction
import actors.EncryptionActor.Encrypted
import actors.{
  AuthenticationActor,
  BlockChainWatcherActor,
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
  TxHash,
  TxMessagingActorSlackChat,
  TxMessagingActorWeb,
  TxPersistenceActor,
  TxUpdate,
  Update,
  Updated
}
import akka.actor.{Actor, ActorRef, ActorSystem, Props}
import akka.pattern.ask
import akka.stream.Materializer
import akka.util.{ByteString, Timeout}
import com.google.common.io.ByteStreams
import com.google.common.util.concurrent.ListenableFuture
import com.google.inject.AbstractModule
import com.mesonomics.playhmacsignatures.{
  EpochSeconds,
  HmacSignature,
  SignatureVerifierService,
  SlackSignatureVerifyAction
}
import com.slack.api.methods.response.auth.AuthTestResponse
import com.slack.api.methods.response.conversations.ConversationsMembersResponse
import com.typesafe.config.ConfigFactory
import controllers.HomeController.EmailFormData
import controllers.SlackSlashCommandController
import dao._
import org.bitcoinj.core.Utils.HEX
import org.bitcoinj.core._
import org.bitcoinj.core.listeners.{
  NewBestBlockListener,
  OnTransactionBroadcastListener
}
import org.bitcoinj.params.MainNetParams
import org.bitcoinj.store.BlockStore
import org.bitcoinj.wallet.Wallet
import org.scalamock.handlers.{CallHandler1, CallHandler8}
import org.scalamock.scalatest.MockFactory
import org.scalamock.util.Defaultable
import pdi.jwt.JwtClaim
import play.api.i18n.{DefaultMessagesApi, Lang, MessagesApi}
import play.api.inject.Injector
import play.api.inject.guice.{GuiceInjectorBuilder, GuiceableModule}
import play.api.libs.json.{JsArray, Json}
import play.api.mvc.{AnyContentAsFormUrlEncoded, BodyParsers}
import play.api.test.Helpers.POST
import play.api.test.{FakeRequest, Helpers}
import play.api.{Configuration, Logging, inject}
import services.{
  BlockChainProvider,
  HooksManagerSlackChat,
  HooksManagerWeb,
  MailManager,
  MainNetParamsProvider,
  MemPoolWatcher,
  MemPoolWatcherService,
  PeerGroupProvider,
  SlackManager,
  SlackSecretsManagerService,
  SodiumEncryptionManager,
  User,
  UserManagerService
}
import slack.BlockMessages.{
  MESSAGE_NEW_TRANSACTION,
  MESSAGE_TOO_MANY_OUTPUTS,
  MESSAGE_TO_ADDRESSES,
  MESSAGE_TRANSACTION_HASH
}
import slick.BtcPostgresProfile.api._
import slick._
import slick.dbio.{DBIO, Effect}
import slick.jdbc.JdbcBackend.Database
import slick.lifted.TableQuery
import slick.sql.{FixedSqlAction, FixedSqlStreamingAction}

import java.io.{FileNotFoundException, InputStream}
import java.net.URI
import java.time.{Clock, LocalDateTime}
import javax.inject.Provider
import scala.collection.compat.immutable.ArraySeq
import scala.collection.mutable
import scala.concurrent.duration.{Duration, DurationInt, FiniteDuration}
import scala.concurrent.{ExecutionContext, Future}
import scala.io.Source
import scala.jdk.CollectionConverters._
import scala.util.{Failure, Success, Try}

//noinspection TypeAnnotation
// scalafix:off
object Fixtures {

  implicit val d = new Defaultable[ListenableFuture[_]] {
    override val default = null
  }

  def resourceAsInputStream(resource: String): Try[InputStream] = {
    val classLoader = Thread.currentThread().getContextClassLoader
    Option(classLoader.getResourceAsStream(resource)) match {
      case Some(in) => Success(in)
      case None =>
        Failure(
          new FileNotFoundException(
            s"resource '$resource' was not found in the classpath from the given classloader."
          )
        )
    }
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
      extends HookManagerMock[SlackChannelId, SlackChatHookPlainText]

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
      extends MockHookManagerActor[SlackChannelId, SlackChatHookPlainText]

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

  trait SlackSignatureVerifierFixtures extends MockFactory {
    val mockSlackSignatureVerifierService = mock[SignatureVerifierService]
    val fakeSlackSignatureHeaders = Array(
      ("X-Slack-Request-Timestamp", "1663156082"),
      (
        "X-Slack-Signature",
        "v0=d1c387a20da72e5e07de4e2fb7e93cd9b44c2caa118868aad99c3b20c93de73a"
      )
    )
  }

  trait DefaultBodyParserFixtures {
    env: HasExecutionContext with HasActorSystem =>
    private implicit val system = actorSystem
    implicit val mat: Materializer = Materializer(system)
    val bodyParser = new BodyParsers.Default()
  }

  trait Auth0ActionFixtures {
    env: ConfigurationFixtures
      with DefaultBodyParserFixtures
      with HasExecutionContext =>

    class MockAuth0Action(
        override protected val validateJwt: String => Try[JwtClaim]
    ) extends Auth0ValidateJWTAction(bodyParser, config)(executionContext) {}

    val claim = JwtClaim()
    val mockAuth0ActionAlwaysSuccess = new MockAuth0Action(_ => Success(claim))
    val mockAuth0ActionAlwaysFail = new MockAuth0Action(_ =>
      Failure(new Exception("invalid JWT from test"))
    )
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

    val pgs = new PeerGroupProvider() {
      lazy val get = mockPeerGroup
    }
    val memPoolWatcherActor = actorSystem.actorOf(
      MemPoolWatcherActor.props(
        pgs,
        new MainNetParamsProvider(),
        injector.instanceOf[DatabaseExecutionContext]
      )
    )
    val memPoolWatcher =
      new MemPoolWatcher(memPoolWatcherActor)(actorSystem, executionContext) {
        override def initialiseFuture(): Future[Unit] = {
          Future {
            ()
          }
        }
      }

    class MemPoolWatcherModule() extends AbstractModule {
      override def configure(): Unit = {
        super.configure()
        bind(classOf[MemPoolWatcher]).toInstance(memPoolWatcher)
      }
    }
  }

  trait TransactionFixtures {
    env: MemPoolWatcherFixtures =>
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

  trait WebSocketFixtures extends MockFactory {
    env: HasActorSystem =>
    val actorSystem: ActorSystem
    val mockWs = mock[WebSocketMock]
    val mockWsActor = actorSystem.actorOf(MockWebsocketActor.props(mockWs))
  }

  trait MainNetParamsFixtures {
    val netParamsProvider = new MainNetParamsProvider
    val params = netParamsProvider.get
  }

  trait BlockFixtures {
    env: MainNetParamsFixtures =>
    val block169482: Try[Block] = resourceAsInputStream("block169482.dat") map {
      in =>
        params.getDefaultSerializer
          .makeBlock(ByteStreams.toByteArray(in))
    }
  }

  trait BlockChainWatcherFixtures extends MockFactory {
    env: MainNetParamsFixtures with HasActorSystem =>
    val wallets = new java.util.LinkedList[Wallet]()
    val blockStore = mock[BlockStore]
    val mockStoredBlock = mock[StoredBlock]
    val genesisBlock: Block = params.getGenesisBlock

    (mockStoredBlock.getHeight _).expects().returning(0)
    (mockStoredBlock.getHeader _)
      .expects()
      .returning(genesisBlock)
      .atLeastOnce()
    (mockStoredBlock.getPrev _).expects(*).returning(null)
    (blockStore.getChainHead _).expects().returning(mockStoredBlock)

    // noinspection NotImplementedCode
    class MockBlockChain
        extends AbstractBlockChain(params, wallets, blockStore) {
      override def addToBlockStore(
          storedPrev: StoredBlock,
          block: Block
      ): StoredBlock = ???

      override def addToBlockStore(
          storedPrev: StoredBlock,
          header: Block,
          txOutputChanges: TransactionOutputChanges
      ): StoredBlock = ???

      override def rollbackBlockStore(height: Int): Unit = ???

      override def doSetChainHead(chainHead: StoredBlock): Unit = ???

      override def notSettingChainHead(): Unit = ???

      override def getStoredBlockInCurrentScope(
          hash: Sha256Hash
      ): StoredBlock = ???

      override def shouldVerifyTransactions(): Boolean = ???

      override def connectTransactions(
          height: Int,
          block: Block
      ): TransactionOutputChanges = ???

      override def connectTransactions(
          newBlock: StoredBlock
      ): TransactionOutputChanges = ???

      override def disconnectTransactions(block: StoredBlock): Unit = ???
    }

    val mockBlockChain = mock[MockBlockChain]
    (mockBlockChain.getChainHead _).expects().once()
    (mockBlockChain.addNewBestBlockListener(_: NewBestBlockListener)).expects(*)

    val mockBlockChainProvider = new BlockChainProvider {
      override val get = mockBlockChain
    }

    def makeBlockChainWatcherActor = actorSystem.actorOf(
      BlockChainWatcherActor.props(
        mockBlockChainProvider,
        netParamsProvider
      )
    )

    lazy val blockChainWatcherActor = makeBlockChainWatcherActor

    class BlockChainWatcherModule() extends AbstractModule {
      override def configure(): Unit = {
        super.configure()
        bind(classOf[BlockChainProvider]).toInstance(mockBlockChainProvider)
      }
    }
  }

  trait ClockFixtures {
    def now: LocalDateTime =
      java.time.LocalDateTime.parse("2022-09-07T00:13:30")
  }

  trait MemPoolWatcherFixtures extends MockFactory {

    val mainNetParams = MainNetParams.get()

    class MainNetPeerGroup extends PeerGroup(mainNetParams)

    val mockMemPoolWatcher = mock[MemPoolWatcherService]
    val mockPeerGroup = mock[MainNetPeerGroup]
    (mockPeerGroup
      .addOnTransactionBroadcastListener(_: OnTransactionBroadcastListener))
      .expects(*)

    def memPoolWatcherExpectations(
        ch: CallHandler1[ActorRef, Unit]
    ): ch.Derived = {
      ch.never()
    }

    def peerGroupExpectations(): Unit

    memPoolWatcherExpectations((mockMemPoolWatcher.addListener _).expects(*))
    peerGroupExpectations()
  }

  trait ConfigurationFixtures {
    val config = Configuration(ConfigFactory.load("application.test.conf"))
  }

  trait ActorGuiceFixtures extends ProvidesInjector {
    env: ConfigurationFixtures
      with MemPoolWatcherFixtures
      with BlockChainWatcherFixtures
      with HasActorSystem
      with HasBindModule =>

    def builder = new GuiceInjectorBuilder()
      .bindings(bindModule)
      .overrides(inject.bind(classOf[Configuration]).toInstance(config))
      .overrides(inject.bind(classOf[ActorSystem]).toInstance(actorSystem))
      .overrides(
        inject
          .bind(classOf[BlockChainProvider])
          .toInstance(mockBlockChainProvider)
      )
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
      Encrypted(
        cipherText = Array[Byte] {
          1
        },
        nonce = Array[Byte] {
          1
        }
      )
    val encryptedToken2 =
      Encrypted(
        cipherText = Array[Byte] {
          2
        },
        nonce = Array[Byte] {
          2
        }
      )
    val originalThreshold = 100L
    val newThreshold = 200L
    val key = SlackChannelId("#test")
    val hook = SlackChatHookPlainText(
      key,
      threshold = originalThreshold,
      isRunning = true,
      token = "test_token_1"
    )
    val stoppedHook = hook.copy(isRunning = false)
    val newHook = SlackChatHookPlainText(
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

  trait DatabaseInitializer {
    env: HasDatabase with HasExecutionContext =>
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
      extends HookDaoTestLogic[SlackChannelId, SlackChatHookPlainText] {
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

  trait WebhookDaoFixtures {
    env: ProvidesInjector =>
    val hookDao = injector.instanceOf[WebhookDao]
  }

  trait WebhookActorFixtures {
    env: ProvidesInjector with HasActorSystem =>
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
    val slackTeam = SlackTeam(
      teamId,
      teamUserId,
      botId,
      accessToken,
      teamName,
      registeredUserId
    )
    val updatedSlackTeam = slackTeam.copy(teamName = "updated")
  }

  trait SlickSlashCommandFixtures {
    env: SlackSignatureVerifierFixtures =>
    val channelId = SlackChannelId("1234")
    val channelIdBad = SlackChannelId("4321")
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

    def fakeRequestValidNoSignature(command: String, amount: String) =
      FakeRequest(POST, "/")
        .withFormUrlEncodedBody(
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

    def fakeRequestValidNoSignatureBadChannel(command: String, amount: String) =
      FakeRequest(POST, "/")
        .withFormUrlEncodedBody(
          "token" -> testToken,
          "team_id" -> slashCommandTeamId.value,
          "team_domain" -> "",
          "channel_id" -> channelIdBad.value,
          "channel_name" -> "testChannelBad",
          "user_id" -> "91011",
          "user_name" -> "test-user",
          "command" -> command,
          "text" -> amount,
          "is_enterprise_install" -> "false"
        )

    def withFakeSlackSignatureHeaders[T](
        request: FakeRequest[T]
    ): FakeRequest[T] =
      request.withHeaders(
        ArraySeq.unsafeWrapArray(fakeSlackSignatureHeaders): _*
      )

    def fakeRequestValid(
        command: String,
        amount: String
    ): FakeRequest[AnyContentAsFormUrlEncoded] =
      withFakeSlackSignatureHeaders(
        fakeRequestValidNoSignature(command, amount)
      )

    def fakeRequestValidBadChannel(
        command: String,
        amount: String
    ): FakeRequest[AnyContentAsFormUrlEncoded] =
      withFakeSlackSignatureHeaders(
        fakeRequestValidNoSignatureBadChannel(command, amount)
      )

    val testUserId = "testUser"
    val mockMembers = List(testUserId).asJava
    val resAuth = new AuthTestResponse
    resAuth.setUserId(testUserId)
    val resConvMembersResponse = new ConversationsMembersResponse
    resConvMembersResponse.setMembers(mockMembers)
    val resConvMembersResponseBad = new ConversationsMembersResponse
    resConvMembersResponseBad.setMembers(List("wrongUser").asJava)
  }

  trait SlackSlashCommandControllerFixtures {
    env: ConfigurationFixtures
      with SlickSlashCommandHistoryDaoFixtures
      with SlickSlackTeamDaoFixtures
      with SlackChatHookDaoFixtures
      with SlackChatActorFixtures
      with MessagesFixtures
      with SlackManagerFixtures
      with SlackSignatureVerifierFixtures
      with HasExecutionContext
      with HasActorSystem
      with DefaultBodyParserFixtures =>

    private implicit val ec = executionContext
    private implicit val system = actorSystem

    val slackSignatureVerifyAction = new SlackSignatureVerifyAction(
      bodyParser,
      config,
      mockSlackSignatureVerifierService
    )

    val slackSlashCommandController = new SlackSlashCommandController(
      Helpers.stubControllerComponents(),
      slashCommandHistoryDao = slickSlashCommandHistoryDao,
      slackTeamDao = slickSlackTeamDao,
      hooksManager = new HooksManagerSlackChat(hookDao, hooksActor),
      messagesApi,
      mockSlackManagerService,
      slackSignatureVerifyAction
    )

    val signatureVerifierExpectations =
      (mockSlackSignatureVerifierService
        .validate(_: Clock)(_: Duration)(
          _: (EpochSeconds, ByteString) => String
        )(
          _: Array[Byte] => HmacSignature
        )(_: String)(
          _: EpochSeconds,
          _: ByteString,
          _: HmacSignature
        ))
        .expects(*, *, *, *, *, *, *, *)
        .anyNumberOfTimes()

    def setSignatureVerifierExpectations(): CallHandler8[
      Clock,
      Duration,
      (EpochSeconds, ByteString) => String,
      Array[Byte] => HmacSignature,
      String,
      EpochSeconds,
      ByteString,
      HmacSignature,
      Try[ByteString]
    ] =
      signatureVerifierExpectations.returning(Success(ByteString("valid")))

    setSignatureVerifierExpectations()
  }

  trait SlickSlashCommandHistoryDaoFixtures {
    env: ProvidesInjector =>
    val injector: Injector
    val slickSlashCommandHistoryDao =
      injector.instanceOf[SlickSlashCommandHistoryDao]
  }

  trait SlickTransactionUpdateDaoFixtures {
    env: ProvidesInjector =>
    val injector: Injector
    val slickTransactionUpdateDao =
      injector.instanceOf[SlickTransactionUpdateDao]
  }

  trait TxUpdateFixtures {
    val timeStamp = java.time.LocalDateTime.of(2001, 1, 1, 0, 0)
    val testHash = TxHash("testHash")
    val tx =
      TxUpdate(testHash, 10, timeStamp, isPending = true, List(), List(), None)
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
    val slackAuthSecret: Secret = Secret(Array(0x00, 0xff).map(_.toByte))
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
    val feedbackFormType = "Feedback"
    val supportFormType = "Support"
    val supportMessage = "This is a test support message."
    val expectedValidEmailSubject = "Feedback - testName test@test.com"
    val expectedValidEmailSubjectSupport = "Support - testName test@test.com"
    val feedbackFormAttrs: Map[String, String] = Map[String, String](
      "formType" -> feedbackFormType,
      "name" -> emailName,
      "email" -> emailAddress,
      "message" -> feedbackMessage
    )
    val supportFormAttrs: Map[String, String] = Map[String, String](
      "formType" -> supportFormType,
      "name" -> emailName,
      "email" -> emailAddress,
      "message" -> supportMessage
    )

    def emailFormSubmission(attrs: Map[String, String]) =
      FakeRequest(POST, "/").withFormUrlEncodedBody(
        attrs.toSeq: _*
      )

    def emailFormData(attrs: Map[String, String]) =
      EmailFormData(
        attrs("formType"),
        attrs("name"),
        attrs("email"),
        attrs("message")
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
    val deleteChannelRequestBody = ByteString(
      Json
        .parse("""
  {
    "event" :
    {
      "type" : "channel_deleted",
      "channel" : "1234"
    }
  }
  """).toString()
    )
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

  trait EncryptionActorFixtures {
    env: HasActorSystem =>
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

  trait WebhooksActorFixtures {
    env: HasActorSystem with ProvidesInjector =>
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

  trait HasMessagesApi {
    val messagesApi: MessagesApi
  }

  trait MessagesFixtures extends HasMessagesApi {
    implicit val lang: Lang = Lang("en")
    val messagesApi = new DefaultMessagesApi(
      Map(
        "en" -> Map(
          MESSAGE_NEW_TRANSACTION -> "New transaction with value",
          MESSAGE_TRANSACTION_HASH -> "Transaction Hash",
          MESSAGE_TO_ADDRESSES -> "to addresses",
          MESSAGE_TOO_MANY_OUTPUTS -> "Too many inputs",
          "slackResponse.currencyError" -> "I currently only provide alerts for BTC, but other currencies are coming soon."
        )
      )
    )
  }

}

// scalafix:on
