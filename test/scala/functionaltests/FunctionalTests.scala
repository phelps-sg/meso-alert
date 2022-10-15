package functionaltests

import org.openqa.selenium.firefox.{FirefoxDriver, FirefoxOptions}
import org.openqa.selenium.support.ui.{ExpectedConditions, WebDriverWait}
import org.openqa.selenium._
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
  val slackEmail: String = System.getenv("SLACK_TEST_EMAIL")
  val slackPassword: String = System.getenv("SLACK_TEST_PASSWORD")
  val headless: Boolean = System.getenv("SELENIUM_SHOW_WINDOW") == null
  val stagingURL: String = System.getenv("STAGING_URL") match {
    case url: String => url
    case null        => "https://meso-alert-staging.eu.ngrok.io"
  }

  private val options = new FirefoxOptions().setHeadless(headless)

  implicit val webDriver: FirefoxDriver = new FirefoxDriver(options)

  setCaptureDir("./captures")

  implicitlyWait(Span(20, Seconds))

  def slackSignIn(workspace: String, email: String, pwd: String): Unit = {
    go to "https://slack.com/workspace-signin"
    delete all cookies
    reloadPage()
    checkForCookieMessage()
    textField("domain").value = workspace
    pressKeys(Keys.ENTER.toString)
    click on id("email")
    pressKeys(email)
    pwdField("password").value = pwd
    pressKeys(Keys.ENTER.toString)
  }

//  def inviteToChannel(botName: String): Unit = {
//    explicitWait()
//    pressKeys(s"@$botName")
//    clickOn(By.xpath("/html/body/div[9]/div/div/div/div/div/ul/li/div"))
//    explicitWait()
//    pressKeys(Keys.ENTER.toString)
//    pressKeys(Keys.ENTER.toString)
//  }

  def removeFromChannel(botName: String): Unit = {
    explicitWait()
    clickOnWithJs(
      By.xpath(
        "//span[contains(@class, 'p-channel_sidebar__name') and text()='testing']"
      )
    )
    webDriver
      .findElement(By.className("ql-editor"))
      .sendKeys(s"/kick @$botName")
    explicitWait()
    pressKeys(Keys.ENTER.toString)
    pressKeys(Keys.ENTER.toString)
    explicitWait()
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
      .findElement(locator)
    val ex: JavascriptExecutor = webDriver
    ex.executeScript("arguments[0].click()", element)
  }

  def createChannel(name: String): Unit = {
    cleanUp()
    find(xpath("//span[text()='Add channels']")).foreach(elem => click on elem)
    find(xpath("//div[text()='Create a new channel']")).foreach(elem =>
      click on elem
    )
    enter(s"$name")
    clickOn(By.xpath("//button[text()='Create']"))
    clickOn(By.xpath("/html/body/div[9]/div/div/div[3]/div/div/div/button"))
  }

  def cleanUp(): Unit = {
    val testChannel = find(
      xpath(
        "//span[contains(@class, 'p-channel_sidebar__name') and text()='testing']"
      )
    )
    testChannel match {
      case Some(elem) =>
        click on elem
        deleteChannel("testing")
      case None =>
    }
  }

  def deleteChannel(name: String): Unit = {
    find(
      xpath(
        s"//span[contains(@class, 'p-view_header__channel_title') and text()='$name']"
      )
    )
      .foreach(elem => click on elem)
    find(xpath("//span[text()='Settings']")).foreach(elem => click on elem)
    find(xpath("//h3[text()='Delete this channel']")).foreach(elem =>
      click on elem
    )
    click on className("c-input_checkbox")
    click on className("c-button--danger")
  }

  def clickOn(locator: By): Unit = {
    new WebDriverWait(webDriver, Duration.ofSeconds(10))
      .ignoring(classOf[StaleElementReferenceException])
      .ignoring(classOf[ElementClickInterceptedException])
      .until(ExpectedConditions.elementToBeClickable(locator))
    webDriver.findElement(locator).click()
  }

  def acceptBlockInsightsCookies(): Unit = {
    val cookies = find("CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll")
    cookies match {
      case Some(_) =>
        clickOn(By.id("CybotCookiebotDialogBodyLevelButtonLevelOptinAllowAll"))
      case None =>
    }
  }

  def explicitWait(): Option[Element] = find(xpath("//wait"))

  "The home page" should "render" in {
    go to stagingURL
    acceptBlockInsightsCookies()
    pageTitle should be("Block Insights - Access free real-time mempool data")
    capture to "HomePage"
  }

  "The feedback form" should "render" in {
    go to s"$stagingURL/feedback"
    pageTitle should be("Feedback Form")
    capture to "FeedbackForm"
  }

  "Entering valid feedback form data and submitting" should "result in 'Message sent successfully'" in {
    go to s"$stagingURL/feedback"
    textField("name").value = "Test Name"
    emailField("email").value = "test@example.com"
    textArea("message").value = "An example feedback message."
    submit()
    capture to "ValidFeedbackSubmission"
    assert(find("alert-success").isDefined)
  }

//  "Login with invalid credentials" should "result in a login error" in {
//    go to stagingURL
//    explicitWait()
//    click on id("qsLoginBtn")
//    textField("username").value = "wrong@email.com"
//    pwdField("password").value = "wrongPassword"
//    click on xpath(
//      "/html/body/div/main/section/div/div/div/form/div[2]/button"
//    )
//    explicitWait()
//    assert(find(className("ulp-input-error-message")).isDefined)
//  }

  "Login with valid credentials" should "result in the user being authenticated" in {
    go to stagingURL
    explicitWait()
    click on id("qsLoginBtn")
    textField("username").value = slackEmail
    pwdField("password").value = slackPassword
    click on xpath(
      "/html/body/div/main/section/div/div/div/form/div[2]/button"
    )
    explicitWait()
    capture to "Login"
    assert(find("dropdownMenuButton").isDefined)
  }

  "canceling bot installation during 'add to slack'" should "redirect to home page " in {
    go to stagingURL
    explicitWait()
    click on id("addToSlackBtn")
    checkForCookieMessage()
    textField("domain").value = workspace
    pressKeys(Keys.ENTER.toString)
    click on id("email")
    pressKeys(slackEmail)
    pwdField("password").value = slackPassword
    click on xpath("//*[@id=\"signin_btn\"]")
    explicitWait()
    click on xpath("/html/body/div[1]/div/form/div/div[2]/a")
    capture to "CancelInstallation"
    pageTitle should be("Block Insights - Access free real-time mempool data")
  }

  "clicking on 'add to slack' and installing the app to a workspace" should
    "result in the successful installation page" in {
      go to stagingURL
      explicitWait()
      click on id("addToSlackBtn")
      checkForCookieMessage()
      new WebDriverWait(webDriver, Duration.ofSeconds(10))
        .ignoring(classOf[StaleElementReferenceException])
        .until(
          ExpectedConditions.elementToBeClickable(
            By.xpath("/html/body/div[1]/div/form/div/div[2]/button")
          )
        )
      webDriver
        .findElement(By.xpath("/html/body/div[1]/div/form/div/div[2]/button"))
        .click()
      capture to "InstallToWorkspace"
      pageTitle should be("Installation successful")
    }

  "Logging out" should "be successful" in {
    go to stagingURL
    explicitWait()
    clickOn(By.id("dropdownMenuButton"))
    click on id("qsLogoutBtn")
    assert(find("qsLoginBtn").isDefined)
    capture to "Logout"
    pageTitle should be("Block Insights - Access free real-time mempool data")
  }

  "issuing command /crypto-alert 100" should "result in correct response message" in {
    slackSignIn(workspace, slackEmail, slackPassword)
    createChannel("testing")
    find(
      xpath(
        "//span[contains(@class, 'p-channel_sidebar__name') and text()='test']"
      )
    )
      .foreach(elem => click on elem)
    webDriver
      .findElement(By.className("ql-editor"))
      .sendKeys("/crypto-alert 100")
    pressKeys(Keys.ENTER.toString)
    capture to "CryptoAlert100"
    val result = find(
      xpath(
        "//span[text()='OK, I will send updates on any BTC transactions exceeding 100 BTC.']"
      )
    )
    assert(result.isDefined)
  }

  "issuing command /pause-alerts" should "result in correct response message" in {
    slackSignIn(workspace, slackEmail, slackPassword)
    find(
      xpath(
        "//span[contains(@class, 'p-channel_sidebar__name') and text()='testing']"
      )
    )
      .foreach(elem => click on elem)
    pressKeys("/pause-alerts")
    pressKeys(Keys.ENTER.toString)
    capture to "PauseAlerts"
    val result =
      find(xpath("//span[text()='OK, I have paused alerts for this channel.']"))
    assert(result.isDefined)
  }

  "issuing command /resume-alerts" should "result in correct response message" in {
    slackSignIn(workspace, slackEmail, slackPassword)
    find(
      xpath(
        "//span[contains(@class, 'p-channel_sidebar__name') and text()='testing']"
      )
    )
      .foreach(elem => click on elem)
    pressKeys("/resume-alerts")
    pressKeys(Keys.ENTER.toString)
    capture to "ResumeAlerts"
    val result =
      find(xpath("//span[text()='OK, I will resume alerts on this channel.']"))
    assert(result.isDefined)
    deleteChannel("testing")
  }
}
