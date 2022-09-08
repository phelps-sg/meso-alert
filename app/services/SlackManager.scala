package services

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.slack.api.methods.AsyncMethodsClient
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.oauth.OAuthV2AccessRequest
import com.slack.api.methods.response.chat.ChatPostMessageResponse
import com.slack.api.methods.response.oauth.OAuthV2AccessResponse
import dao.{RegisteredUserId, SlackBotId, SlackTeam, SlackTeamId, SlackUserId}
import play.api.{Configuration, Logging}
import slack.FutureConverters.BoltFuture
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
}

@Singleton
class SlackManager @Inject() (
    protected val config: Configuration,
    protected val slackClientExecutionContext: SlackClientExecutionContext
) extends SlackManagerService
    with SlackClient
    with Logging {

  protected val slackMethods: AsyncMethodsClient = slack.methodsAsync()

  implicit val ec: ExecutionContext = slackClientExecutionContext

  override def oauthV2Access(
      request: OAuthV2AccessRequest,
      registeredUserId: RegisteredUserId
  ): Future[SlackTeam] = {
    logger.debug(s"Making oauthV2access API call for $registeredUserId... ")
    slackMethods.oauthV2Access(request).asScalaFuture.map {
      response: OAuthV2AccessResponse => {
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
    slackMethods.chatPostMessage(request).asScalaFuture
  }
}
