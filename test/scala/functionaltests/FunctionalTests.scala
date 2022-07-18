package functionaltests

import actors.MemPoolWatcherActor
import com.google.inject.{AbstractModule, Inject}
import org.bitcoinj.core.{NetworkParameters, PeerGroup}
import org.openqa.selenium.firefox.{FirefoxDriverLogLevel, FirefoxOptions}
import org.scalatest.TestData
import org.scalatestplus.play.guice.GuiceOneServerPerTest
import org.scalatestplus.play.{FirefoxFactory, OneBrowserPerSuite, PlaySpec}
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import play.libs.akka.AkkaGuiceSupport
import postgres.PostgresContainer
import services.PeerGroupSelection
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, jdbc}
import unittests.Fixtures.MemPoolWatcherFixtures

import javax.inject.{Provider, Singleton}

@Singleton
class TestMemPoolWatcherActor @Inject() (peerGroupSelection: PeerGroupSelection,
                                          databaseExecutionContext: DatabaseExecutionContext)
  extends MemPoolWatcherActor(peerGroupSelection, databaseExecutionContext)  {

  override def initialisePeerGroup(): Unit = {
    logger.debug("No initialisation in test environment")
  }
}

class FunctionalTests extends PlaySpec
  with PostgresContainer
  with OneBrowserPerSuite
  with FirefoxFactory
  with GuiceOneServerPerTest with
  MemPoolWatcherFixtures {

  val testPeerGroup = new PeerGroup(mainNetParams)

  @Singleton
  class TestPeerGroupSelection extends PeerGroupSelection {
    val params: NetworkParameters = mainNetParams
    lazy val get: PeerGroup = testPeerGroup
  }

  override lazy val firefoxOptions: FirefoxOptions =
    new FirefoxOptions()
      .setHeadless(true)
      .setLogLevel(FirefoxDriverLogLevel.INFO)

  class TestModule extends AbstractModule with AkkaGuiceSupport {

    override def configure(): Unit = {
      bind(classOf[Database]).toProvider(new Provider[Database] {
        val get: jdbc.JdbcBackend.Database = database
      })
      bind(classOf[PeerGroupSelection]).toInstance(new TestPeerGroupSelection())
      bindActor(classOf[TestMemPoolWatcherActor], "mem-pool-actor")
    }
  }

  override def newAppForTest(td: TestData): Application = {
    val builder = GuiceApplicationBuilder(overrides = List(new TestModule()))
    builder.build()
  }

  "The home page" must {
    "render" in {
      go to s"http://localhost:$port/"
      pageTitle mustBe "Welcome to Play"
    }
  }

}
