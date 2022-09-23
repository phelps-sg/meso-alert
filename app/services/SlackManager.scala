package services

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.auth.AuthTestRequest
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.conversations.ConversationsMembersRequest
import com.slack.api.methods.request.oauth.OAuthV2AccessRequest
import com.slack.api.methods.response.auth.AuthTestResponse
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.methods.response.conversations.ConversationsMembersResponse
import com.slack.api.methods.response.oauth.OAuthV2AccessResponse
import dao._
import play.api.{Configuration, Logging}
import slack.SlackClient
import slick.SlackClientExecutionContext

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[SlackManager])
trait SlackManagerService {

  def oauthV2Access(
      request: OAuthV2AccessRequest,
      registeredUserId: RegisteredUserId
  ): Future[SlackTeam]

  def chatPostMessage(
      request: ChatPostMessageRequest
  ): Future[ChatPostMessageResponse]

  def authTest(
      request: AuthTestRequest
  ): Future[AuthTestResponse]

  def conversationsMembers(
      request: ConversationsMembersRequest
  ): Future[ConversationsMembersResponse]
}

/** A wrapper around the BOLT API. Unlike Bolt: i) the methods in this class
  * return mockable objects, and ii) rather than use methodsAsync() provided by
  * BOLT we wrap API invocations in Futures, supplying our own custom execution
  * context so that we can more easily configure the underlying thread-pool.
  */
@Singleton
class SlackManager @Inject() (
    protected val config: Configuration,
    protected val slackClientExecutionContext: SlackClientExecutionContext
) extends SlackManagerService
    with SlackClient
    with Logging {

  protected val slackMethods: MethodsClient = slack.methods()

  implicit val ec: ExecutionContext = slackClientExecutionContext

  override def oauthV2Access(
      request: OAuthV2AccessRequest,
      registeredUserId: RegisteredUserId
  ): Future[SlackTeam] = {
    logger.debug(s"Making oauthV2access API call for $registeredUserId... ")
    Future { slackMethods.oauthV2Access(request) } map {
      response: OAuthV2AccessResponse =>
        {
          logger.debug(s"Received oauthV2access response for $registeredUserId")
          SlackTeam(
            teamId = SlackTeamId(response.getTeam.getId),
            userId = SlackUserId(response.getAuthedUser.getId),
            botId = SlackBotId(response.getBotUserId),
            accessToken = response.getAccessToken,
            teamName = response.getTeam.getName,
            registeredUserId
          )
        }
    }
  }

  override def chatPostMessage(
      request: ChatPostMessageRequest
  ): Future[ChatPostMessageResponse] = {
    Future { slackMethods.chatPostMessage(request) }
  }

  override def authTest(request: AuthTestRequest): Future[AuthTestResponse] = {
    Future { slackMethods.authTest(request) }
  }

  override def conversationsMembers(
      request: ConversationsMembersRequest
  ): Future[ConversationsMembersResponse] = {
    Future { slackMethods.conversationsMembers(request) }
  }
}
