package functionaltests

import org.openqa.selenium.{
  By,
  ElementClickInterceptedException,
  JavascriptExecutor,
  Keys,
  StaleElementReferenceException
}
import org.openqa.selenium.support.ui.ExpectedConditions
import org.openqa.selenium.support.ui.WebDriverWait
import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxOptions}
import org.scalatest.flatspec
import org.scalatest.matchers.should
import org.scalatest.time.{Seconds, Span}
import org.scalatestplus.selenium.WebBrowser

import java.time.Duration

class FunctionalTests
    extends flatspec.AnyFlatSpec
    with should.Matchers
    with WebBrowser {

  val workspace: String = System.getenv("SLACK_TEST_WORKSPACE")
  private val options = new FirefoxOptions().setHeadless(true)
  implicit val webDriver: FirefoxDriver = new FirefoxDriver(options)
  implicitlyWait(Span(20, Seconds))
  val slackEmail: String = System.getenv("SLACK_TEST_EMAIL")
  val slackPassword: String = System.getenv("SLACK_TEST_PASSWORD")

  val stagingURL: String = "https://f34d1cfcb2d9.eu.ngrok.io"

  def slackSignIn(workspace: String, email: String, pwd: String): Unit = {
    go to "https://slack.com/workspace-signin"
    delete all cookies

    reloadPage()
    checkForCookieMessage
    textField("domain").value = workspace
    pressKeys(Keys.ENTER.toString)
    click on id("email")
    pressKeys(email)
    pwdField("password").value = pwd
    pressKeys(Keys.ENTER.toString)
  }

  def inviteToChannel(botName: String): Unit = {
    find(xpath("//wait"))
    pressKeys(s"@$botName")
    clickOn(By.xpath("/html/body/div[9]/div/div/div/div/div/ul/li/div"))
    clickOn(
      By.xpath(
        "/html/body/div[2]/div/div[2]/div[4]/div/div/div[3]/div/div[2]/div/div/div/div[2]/div/div/div/div[3]/div[3]/span/button[1]"
      )
    )
    pressKeys(Keys.ENTER.toString)
  }

  def removeFromChannel(botName: String): Unit = {
    find(xpath("//wait"))
    clickOnWithJs(
      By.xpath(
        "//span[contains(@class, 'p-channel_sidebar__name') and text()='testing']"
      )
    )
    webDriver
      .findElement(By.className("ql-editor"))
      .sendKeys(s"/kick @$botName")
    find(xpath("//wait"))
    pressKeys(Keys.ENTER.toString)
    pressKeys(Keys.ENTER.toString)
    click on className("c-button--danger")
  }

  def checkForCookieMessage(): Unit = {
    val cookies = find("onetrust-reject-all-handler")
    cookies match {
      case Some(_) => click on id("onetrust-reject-all-handler")
      case None    =>
    }
  }

  def clickOnWithJs(locator: By): Object = {
    val element = webDriver
      .findElement(locator);
    val ex: JavascriptExecutor = webDriver;
    ex.executeScript("arguments[0].click()", element);
  }

  def createChannel(name: String): Unit = {
    cleanUp()
    find(xpath("//span[text()='Add channels']")).map(elem => click on (elem))
    find(xpath("//div[text()='Create a new channel']")).map(elem =>
      click on (elem)
    )
    enter(s"$name")
    clickOn(By.xpath("//button[text()='Create']"))
    clickOn(By.xpath("/html/body/div[9]/div/div/div[3]/div/div/div/button"))
  }

  def deleteChannel(name: String): Unit = {
    find(
      xpath(
        s"//span[contains(@class, 'p-view_header__channel_title') and text()='$name']"
      )
    )
      .map(elem => click on (elem))
    find(xpath("//span[text()='Settings']")).map(elem => click on (elem))
    find(xpath("//h3[text()='Delete this channel']")).map(elem =>
      click on (elem)
    )
    click on className("c-input_checkbox")
    click on className("c-button--danger")
  }

  def cleanUp(): Unit = {
    val testChannel = find(
      xpath(
        "//span[contains(@class, 'p-channel_sidebar__name') and text()='testing']"
      )
    )
    testChannel match {
      case Some(elem) =>
        click on (elem)
        deleteChannel("testing")
      case None =>
    }
  }

  def clickOn(locator: By): Unit = {
    new WebDriverWait(webDriver, Duration.ofSeconds(10))
      .ignoring(classOf[StaleElementReferenceException])
      .ignoring(classOf[ElementClickInterceptedException])
      .until(ExpectedConditions.elementToBeClickable(locator))
    webDriver.findElement(locator).click
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
      checkForCookieMessage
      textField("domain").value = workspace
      pressKeys(Keys.ENTER.toString)
      click on id("email")
      pressKeys(slackEmail)
      pwdField("password").value = slackPassword
      click on xpath("//*[@id=\"signin_btn\"]")
      new WebDriverWait(webDriver, Duration.ofSeconds(10))
        .ignoring(classOf[StaleElementReferenceException])
        .until(
          ExpectedConditions.elementToBeClickable(
            By.xpath("/html/body/div[1]/div/form/div/div[2]/button")
          )
        )
      webDriver
        .findElement(By.xpath("/html/body/div[1]/div/form/div/div[2]/button"))
        .click
//    click on className("c-button--primary")
      pageTitle should be("Installation successful")
    }

  "inviting bot to a channel using the '@' command" should "be successful" in {
    slackSignIn(workspace, slackEmail, slackPassword)
    createChannel("testing")
    inviteToChannel("block-insights-staging")
    removeFromChannel("block-insights-staging")
  }
//
  "issuing command /crypto-alert 100" should "result in correct response message" in {
    slackSignIn(workspace, slackEmail, slackPassword)
    find(
      xpath(
        "//span[contains(@class, 'p-channel_sidebar__name') and text()='test']"
      )
    )
      .map(elem => click on (elem))
    inviteToChannel("block-insights-staging")
    webDriver
      .findElement(By.className("ql-editor"))
      .sendKeys("/crypto-alert 100")
    pressKeys(Keys.ENTER.toString)
    val result = find(
      xpath(
        "//span[text()='OK, I will send updates on any BTC transactions exceeding 100 BTC.']"
      )
    )
    assert(!result.isEmpty)
  }

  "issuing command /pause-alerts" should "result in correct response message" in {
    slackSignIn(workspace, slackEmail, slackPassword)
    find(
      xpath(
        "//span[contains(@class, 'p-channel_sidebar__name') and text()='testing']"
      )
    )
      .map(elem => click on (elem))
    pressKeys("/pause-alerts")
    pressKeys(Keys.ENTER.toString)
    val result =
      find(xpath("//span[text()='OK, I have paused alerts for this channel.']"))
    assert(!result.isEmpty)
  }

  "issuing command /resume-alerts" should "result in correct response message" in {
    slackSignIn(workspace, slackEmail, slackPassword)
    find(
      xpath(
        "//span[contains(@class, 'p-channel_sidebar__name') and text()='testing']"
      )
    )
      .map(elem => click on (elem))
    pressKeys("/resume-alerts")
    pressKeys(Keys.ENTER.toString)
    val result =
      find(xpath("//span[text()='OK, I will resume alerts on this channel.']"))
    assert(!result.isEmpty)
    deleteChannel("testing")
  }
}
