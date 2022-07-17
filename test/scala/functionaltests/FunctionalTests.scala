package functionaltests

import com.google.inject.AbstractModule
import org.openqa.selenium.firefox.{FirefoxDriverLogLevel, FirefoxOptions, FirefoxProfile}
import org.scalatest.TestData
import org.scalatestplus.play.guice.GuiceOneServerPerTest
import org.scalatestplus.play.{FirefoxFactory, OneBrowserPerSuite, PlaySpec}
import play.api.Application
import play.api.inject.guice.GuiceApplicationBuilder
import postgres.PostgresContainer
import slick.jdbc
import slick.jdbc.JdbcBackend.Database

import javax.inject.Provider

class FunctionalTests extends PlaySpec
  with PostgresContainer
  with OneBrowserPerSuite with FirefoxFactory with GuiceOneServerPerTest {

  override lazy val firefoxOptions: FirefoxOptions =
    new FirefoxOptions()
      .setHeadless(true)
      .setLogLevel(FirefoxDriverLogLevel.INFO)

  class TestModule extends AbstractModule {
    override def configure(): Unit = {
      bind(classOf[Database]).toProvider(new Provider[Database] {
        val get: jdbc.JdbcBackend.Database = database
      })
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
