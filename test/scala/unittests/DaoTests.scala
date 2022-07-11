package unittests

import actors.EncryptionActor.Encrypted
import akka.actor.ActorSystem
import akka.testkit.TestKit
import dao.{SlackChatHookEncrypted, SlashCommand, TransactionUpdate}
import org.scalatest.BeforeAndAfterAll
import org.scalatest.concurrent.ScalaFutures
import org.scalatest.matchers.should
import org.scalatest.time.{Millis, Seconds, Span}
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.inject.guice.GuiceableModule
import postgres.PostgresContainer
import slick.BtcPostgresProfile.api._
import slick.Tables
import unittests.Fixtures.{ConfigurationFixtures, DatabaseGuiceFixtures, DatabaseInitializer, EncryptionActorFixtures, EncryptionManagerFixtures, SlackChatDaoTestLogic, SlackChatHookDaoFixtures, SlackChatHookFixtures, SlickSlackTeamDaoFixtures, SlickSlackTeamFixtures, SlickSlashCommandFixtures, SlickSlashCommandHistoryDaoFixtures, SlickTransactionUpdateDaoFixtures, TxUpdateFixtures, WebhookDaoFixtures, WebhookDaoTestLogic, WebhookFixtures}

// scalafix:off

class DaoTests extends TestKit(ActorSystem("meso-alert-dao-tests"))
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
  }

  implicit override val patienceConfig: PatienceConfig =
    PatienceConfig(timeout = Span(20, Seconds), interval = Span(5, Millis))

  "WebhookDao" should {

    trait TestFixtures extends FixtureBindings with DatabaseGuiceFixtures
      with WebhookDaoFixtures with WebhookFixtures with WebhookDaoTestLogic {
    }

    "record a web hook in the database" in new TestFixtures {
      insertHook().futureValue should matchPattern { case (1, Seq(`hook`)) => }
    }

    "return an existing hook by key" in new TestFixtures {
      findHook().futureValue should matchPattern { case (1, Some(`hook`)) => }
    }

    "return None when attempting to find a non existent hook" in new TestFixtures {
      findNonExistentHook().futureValue should matchPattern { case None => }
    }

    "update an existing hook" in new TestFixtures {
      updateHook().futureValue should matchPattern { case (1, 1, Seq(`newHook`)) => }
    }

  }

  "SlackChatHookDao" should {

    trait TestFixtures extends FixtureBindings with ConfigurationFixtures with DatabaseGuiceFixtures
      with EncryptionActorFixtures with EncryptionManagerFixtures
      with SlackChatHookDaoFixtures with SlackChatHookFixtures with SlackChatDaoTestLogic {

      encryptionManager.init()
    }

    "record a slack chat hook in the database" in new TestFixtures {
      insertHook().futureValue should matchPattern {
        case (1, Seq(SlackChatHookEncrypted(`key`, _: Encrypted, `originalThreshold`, true))) =>
      }
    }

    "return an existing hook by key" in new TestFixtures {
      findHook().futureValue should matchPattern { case (1, Some(`hook`)) => }
    }

    "return None when attempting to find a non existent hook" in new TestFixtures {
      findNonExistentHook().futureValue should matchPattern { case None => }
    }

    "update an existing hook" in new TestFixtures {
      updateHook().futureValue should matchPattern {
        case (1, 1, Seq(SlackChatHookEncrypted(`key`, _: Encrypted, `newThreshold`, true))) =>
      }
    }
  }

  "SlickSlackTeamDao" should {

    trait TestFixtures extends FixtureBindings with DatabaseGuiceFixtures with SlickSlackTeamFixtures
      with SlickSlackTeamDaoFixtures with DatabaseInitializer

    "record a team in the database" in new TestFixtures {
      afterDbInit {
        for {
          n <- slickSlackTeamDao.insertOrUpdate(slackTeam)
          r <- db.run(Tables.slackTeams.result)
        } yield (n, r)
      }.futureValue should matchPattern {
        case (1, Seq(`slackTeam`)) =>
      }
    }

    "find a team in the database" in new TestFixtures {
      afterDbInit {
        for {
          n <- slickSlackTeamDao.insertOrUpdate(slackTeam)
          user <- slickSlackTeamDao.find(teamId)
        } yield (n, user)
      }.futureValue should matchPattern {
        case (1, Some(`slackTeam`)) =>
      }
    }

    "return None when a user with the given user id does not exist" in new TestFixtures {
      afterDbInit {
        for {
          n <- slickSlackTeamDao.insertOrUpdate(slackTeam)
          user <- slickSlackTeamDao.find("nonexistent")
        } yield (n, user)
      }.futureValue should matchPattern {
        case (1, None) =>
      }
    }
  }

  "SlickSlashCommandHistoryDao" should {

    trait TestFixtures extends FixtureBindings with DatabaseGuiceFixtures with SlickSlashCommandFixtures
      with SlickSlashCommandHistoryDaoFixtures with DatabaseInitializer

    "record a slack slash command history" in new TestFixtures {
      afterDbInit {
        for {
          n <- slickSlashCommandHistoryDao.record(slashCommand)
          r <- database.run(Tables.slashCommandHistory.result)
        } yield (n, r)
      }.futureValue should matchPattern {
        case (1, Seq(SlashCommand(Some(_: Int), `channelId`, `command`, `text`, `teamDomain`, `teamId`,
        `channelName`, `userId`, `userName`, `isEnterpriseInstall`, `timeStamp`))) =>
      }
    }
  }

  "SlickTransactionUpdateDao" should {

    trait TestFixtures extends FixtureBindings with DatabaseGuiceFixtures with SlickTransactionUpdateDaoFixtures
      with TxUpdateFixtures with DatabaseInitializer

    "record a TxUpdate" in new TestFixtures {
      afterDbInit {
        for {
          n <- slickTransactionUpdateDao.record(tx)
          r <- database.run(Tables.transactionUpdates.result)
        } yield (n, r)
      }.futureValue should matchPattern {
        case (1, Seq(TransactionUpdate(Some(_: Long), "testHash", 10, `timeStamp`, true ))) =>      }
    }
  }

  override def afterAll(): Unit = {
    TestKit.shutdownActorSystem(system)
  }
}
