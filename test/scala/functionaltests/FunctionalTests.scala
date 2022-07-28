package functionaltests

import org.openqa.selenium.Keys
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxOptions}
import org.scalatest.flatspec
import org.scalatest.matchers.should
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.selenium.WebBrowser
import unittests.Fixtures.ConfigurationFixtures

import java.util.Properties
import javax.mail.Folder
import javax.mail.Session
import javax.mail.Store
import javax.mail.Message

trait EmailFetcher {
  val properties = new Properties()
  properties.put("mail.pop3.host", "pop.gmail.com")
  properties.put("mail.pop3.port", "995")
  properties.put("mail.pop3.starttls.enable", "true")
  val emailSession: Session = Session.getDefaultInstance(properties)
  val store: Store = emailSession.getStore("pop3s")

  def connect(): Unit = {
    store.connect("pop.gmail.com", "", "")
  }

  def getLatestMessage(): Message = {
    val emailFolder = getInbox()
    emailFolder.open(Folder.READ_ONLY)
    val messages = emailFolder.getMessages
    messages(0)
  }

  def getInbox(name: String = "INBOX"): Folder = store.getFolder("INBOX")

}

class FunctionalTests extends flatspec.AnyFlatSpec with should.Matchers with WebBrowser with ConfigurationFixtures {

  val workspace = config.get[String]("slack.testWorkspace")
  implicit val webDriver: FirefoxDriver = new FirefoxDriver(options)
  implicitlyWait(Span(10, Seconds))
  val slackEmail = config.get[String]("slack.testEmail")
  val slackPassword = config.get[String]("slack.testPassword")
  private val options = new FirefoxOptions().setHeadless(false)


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

  def checkForCookieMessage(): Unit = {
    val cookies = find("onetrust-reject-all-handler")
    cookies match {
      case Some(_) => click on id("onetrust-reject-all-handler")
      case None =>
    }
  }

  def inviteToChannel(botName: String): Unit = {
    pressKeys(s"@$botName")
    Thread.sleep(400)
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(400)
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(400)
    pressKeys(Keys.ENTER.toString)
  }

  def removeFromChannel(botName: String): Unit = {
    pressKeys(s"/kick @$botName")
    Thread.sleep(400)
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(400)
    pressKeys(Keys.ENTER.toString)
    Thread.sleep(400)
    click on className("c-button--danger")
  }

  def createChannel(name: String) = {
    cleanUp()
    find(xpath("//span[text()='Add channels']")).map(elem => click on(elem))
    Thread.sleep(400)
    find(xpath("//div[text()='Create a new channel']")).map(elem => click on(elem))
    Thread.sleep(400)
    pressKeys(s"$name")
    click on className("c-button--primary")
    Thread.sleep(400)
    click on className("c-sk-modal__close_button")
  }

  def cleanUp() = {
    val testChannel = find(xpath("//span[contains(@class, 'p-channel_sidebar__name') and text()='test']"))
    testChannel match {
      case Some(elem) =>
        click on(elem)
        deleteChannel("test")
      case None =>
    }
  }

  def deleteChannel(name: String) = {
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
    Thread.sleep(8000)
    click on className("c-button--primary")
    Thread.sleep(8000)
    pageTitle should be("Installation successful")
  }

  "inviting bot to a channel using the '@' command" should "be successful" in {
    slackSignIn(workspace, slackEmail,slackPassword)
    createChannel("test")
    Thread.sleep(2000)
    inviteToChannel("block-insights-staging")
    Thread.sleep(3000)
    removeFromChannel("block-insights-staging")
  }

  "issuing command /crypto-alert 10" should "result in correct response message" in {
    slackSignIn(workspace, slackEmail,slackPassword)
    find(xpath("//span[contains(@class, 'p-channel_sidebar__name') and text()='test']"))
      .map(elem => click on(elem))
    Thread.sleep(2000)
    inviteToChannel("block-insights-staging")
    Thread.sleep(1000)
    pressKeys("/crypto-alert 10")
    Thread.sleep(400)
    pressKeys(Keys.ENTER.toString)
    val result = find(xpath("//span[text()='OK, I will send updates on any BTC transactions exceeding 10 BTC.']"))
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
    Thread.sleep(1000)
    deleteChannel("test")
  }

  "deleting the test channel" should "produce no errors" in {
    slackSignIn(workspace, slackEmail,slackPassword)
    reloadPage()
    cleanUp()
  }
}
