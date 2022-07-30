package functionaltests

import org.openqa.selenium.Keys
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxOptions}
import org.scalatest.flatspec
import org.scalatest.matchers.should
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.selenium.WebBrowser

class FunctionalTests extends flatspec.AnyFlatSpec with should.Matchers with WebBrowser {

  implicit val webDriver: FirefoxDriver = new FirefoxDriver(options)
  implicitlyWait(Span(10, Seconds))
  private val options = new FirefoxOptions()
  val slackEmail: String = System.getenv("SLACK_TEST_EMAIL")
  val slackPassword: String = System.getenv("SLACK_TEST_PASSWORD")
  val workspace: String = System.getenv("SLACK_TEST_WORKSPACE")


  val stagingURL: String = "https://f34d1cfcb2d9.eu.ngrok.io"

  def slackSignIn(workspace: String, email: String, pwd: String): Unit = {
    go to "https://slack.com/workspace-signin"
    delete all cookies
    reloadPage()
    checkForCookieMessage
    textField("domain").value = workspace
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(3000)
    click on id("email")
    pressKeys(email)
    pwdField("password").value=pwd
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(15000)
  }

  def inviteToChannel(botName: String): Unit = {
    pressKeys(s"@$botName")
    Thread.sleep(1500)
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(1500)
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(1500)
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(3000)
  }

  def removeFromChannel(botName: String): Unit = {
    pressKeys(s"/kick @$botName")
    Thread.sleep(1500)
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(1500)
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(1500)
    click on className("c-button--danger")
    Thread.sleep(3000)
  }

  def checkForCookieMessage(): Unit = {
    val cookies = find("onetrust-reject-all-handler")
    cookies match {
      case Some(_) => click on id("onetrust-reject-all-handler")
      case None =>
    }
  }

  def createChannel(name: String): Unit = {
    cleanUp()
    find(xpath("//span[text()='Add channels']")).map(elem => click on(elem))
    Thread.sleep(1500)
    find(xpath("//div[text()='Create a new channel']")).map(elem => click on(elem))
    Thread.sleep(1500)
    pressKeys(s"$name")
    click on className("c-button--primary")
    Thread.sleep(1500)
    click on className("c-sk-modal__close_button")
    Thread.sleep(2000)
  }

  def cleanUp(): Unit = {
    val testChannel = find(xpath("//span[contains(@class, 'p-channel_sidebar__name') and text()='test']"))
    testChannel match {
      case Some(elem) =>
        click on(elem)
        deleteChannel("test")
      case None =>
    }
    Thread.sleep(1000)
  }

  def deleteChannel(name: String): Unit = {
    find(xpath(s"//span[contains(@class, 'p-view_header__channel_title') and text()='$name']"))
      .map(elem => click on(elem))
    Thread.sleep(1000)
    find(xpath("//span[text()='Settings']")).map(elem => click on(elem))
    Thread.sleep(1000)
    find(xpath("//h3[text()='Delete this channel']")).map(elem => click on(elem))
    Thread.sleep(1000)
    click on className("c-input_checkbox")
    Thread.sleep(1000)
    click on className("c-button--danger")
    Thread.sleep(3000)
  }

  "The home page" should "render" in {
    go to stagingURL
    pageTitle should be("Block Insights - Access free real-time mempool data")
  }

  "The feedback form" should "render" in {
    go to s"$stagingURL/feedback"
    pageTitle should be("Feedback Form")
  }

  "Entering valid feedback form data and submitting" should "result in 'Message sent successfully'" in {
    go to s"$stagingURL/feedback"
    textField("name").value = "Test Name"
    emailField("email").value = "test@example.com"
    textArea("message").value = "An example feedback message."
    submit()
    assert(!find("alert-success").isEmpty)
  }

  "clicking on 'add to slack' and installing the app to a workspace" should
    "result in the successful installation page" in {
    go to stagingURL
    click on id("addToSlackBtn")
    Thread.sleep(3000)
    checkForCookieMessage
    textField("domain").value=workspace
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(3000)
    click on id("email")
    pressKeys(slackEmail)
    pwdField("password").value=slackPassword
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(15000)
    click on className("c-button--primary")
    Thread.sleep(8000)
    pageTitle should be("Installation successful")
  }

  "inviting bot to a channel using the '@' command" should "be successful" in {
    slackSignIn(workspace, slackEmail,slackPassword)
    createChannel("test")
    inviteToChannel("block-insights-staging")
    removeFromChannel("block-insights-staging")
  }

  "issuing command /crypto-alert 100" should "result in correct response message" in {
    slackSignIn(workspace, slackEmail,slackPassword)
    find(xpath("//span[contains(@class, 'p-channel_sidebar__name') and text()='test']"))
      .map(elem => click on(elem))
    Thread.sleep(2000)
    inviteToChannel("block-insights-staging")
    pressKeys("/crypto-alert 100")
    Thread.sleep(400)
    pressKeys(Keys.ENTER.toString)
    val result = find(xpath("//span[text()='OK, I will send updates on any BTC transactions exceeding 100 BTC.']"))
    assert(!result.isEmpty)
  }

  "issuing command /pause-alerts" should "result in correct response message" in {
    slackSignIn(workspace, slackEmail,slackPassword)
    find(xpath("//span[contains(@class, 'p-channel_sidebar__name') and text()='test']"))
      .map(elem => click on(elem))
    Thread.sleep(2000)
    pressKeys("/pause-alerts")
    Thread.sleep(400)
    pressKeys(Keys.ENTER.toString)
    val result = find(xpath("//span[text()='OK, I have paused alerts for this channel.']"))
    assert(!result.isEmpty)
  }

  "issuing command /resume-alerts" should "result in correct response message" in {
    slackSignIn(workspace, slackEmail,slackPassword)
    find(xpath("//span[contains(@class, 'p-channel_sidebar__name') and text()='test']"))
      .map(elem => click on(elem))
    Thread.sleep(2000)
    pressKeys("/resume-alerts")
    Thread.sleep(400)
    pressKeys(Keys.ENTER.toString)
    val result = find(xpath("//span[text()='OK, I will resume alerts on this channel.']"))
    assert(!result.isEmpty)
    removeFromChannel("block-insights-staging")
    deleteChannel("test")
  }

  "deleting the test channel" should "produce no errors" in {
    slackSignIn(workspace, slackEmail,slackPassword)
    reloadPage()
    Thread.sleep(5000)
    cleanUp()
  }
}
