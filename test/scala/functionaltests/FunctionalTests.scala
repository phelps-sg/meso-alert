package functionaltests

import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxOptions}
import org.scalatest.flatspec
import org.scalatest.matchers.should
import org.scalatestplus.selenium.WebBrowser

class FunctionalTests extends flatspec.AnyFlatSpec with should.Matchers with WebBrowser {

  private val options = new FirefoxOptions().setHeadless(true)
  implicit val webDriver: FirefoxDriver = new FirefoxDriver(options)

  "The home page" should "render" in {
    go to "https://f34d1cfcb2d9.eu.ngrok.io/"
    pageTitle should be("Block Insights - Access free real-time mempool data")
  }

}
