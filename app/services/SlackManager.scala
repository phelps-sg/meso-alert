package services

import com.google.inject.{ImplementedBy, Inject, Singleton}
import com.slack.api.methods.MethodsClient
import com.slack.api.methods.request.chat.ChatPostMessageRequest
import com.slack.api.methods.request.conversations.{
  ConversationsInfoRequest,
  ConversationsMembersRequest
}
import com.slack.api.methods.request.oauth.OAuthV2AccessRequest
import com.slack.api.methods.response.oauth.OAuthV2AccessResponse
import dao._
import play.api.{Configuration, Logging}
import slack.BlockMessages.Blocks
import slack.FutureConverters.BoltFuture
import slack.SlackClient
import slick.SlackClientExecutionContext

import scala.concurrent.{ExecutionContext, Future}
import scala.jdk.CollectionConverters._

case class SlackConversationInfo(channel: SlackChannelId, isPrivate: Boolean)

@ImplementedBy(classOf[SlackManager])
trait SlackManagerService {

  def oauthV2Access(
      slackClientId: String,
      slackClientSecret: String,
      temporaryCode: String,
      registeredUserId: RegisteredUserId
  ): Future[SlackTeam]

  def chatPostMessage(
      token: SlackAuthToken,
      username: String,
      channel: SlackChannelId,
      text: String,
      blocks: Blocks
  ): Future[Blocks]

  def conversationsInfo(
      token: SlackAuthToken,
      channel: SlackChannelId
  ): Future[SlackConversationInfo]

  def conversationsMembers(
      token: SlackAuthToken,
      channel: SlackChannelId
  ): Future[Set[SlackTeamId]]
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
      slackClientId: String,
      slackClientSecret: String,
      temporaryCode: String,
      registeredUserId: RegisteredUserId
  ): Future[SlackTeam] = {
    logger.debug(s"Making oauthV2access API call for $registeredUserId... ")
    val request = OAuthV2AccessRequest.builder
      .clientId(slackClientId)
      .clientSecret(slackClientSecret)
      .code(temporaryCode)
      .build()
    BoltFuture { slackMethods.oauthV2Access(request) } map {
      response: OAuthV2AccessResponse =>
        {
          logger.debug(s"Received oauthV2access response for $registeredUserId")
          SlackTeam(
            teamId = SlackTeamId(response.getTeam.getId),
            userId = SlackUserId(response.getAuthedUser.getId),
            botId = SlackBotId(response.getBotUserId),
            accessToken = SlackAuthToken(response.getAccessToken),
            teamName = response.getTeam.getName,
            registeredUserId
          )
        }
    }
  }

  override def chatPostMessage(
      token: SlackAuthToken,
      username: String,
      channel: SlackChannelId,
      text: String,
      blocks: Blocks
  ): Future[Blocks] = {
    val request = ChatPostMessageRequest.builder
      .token(token.value)
      .username(username)
      .channel(channel.value)
      .text(text)
      .blocksAsString(blocks.value)
      .build
    BoltFuture { slackMethods.chatPostMessage(request) } map { _ => blocks }
  }

  override def conversationsInfo(
      token: SlackAuthToken,
      channel: SlackChannelId
  ): Future[SlackConversationInfo] = {
    val request = ConversationsInfoRequest.builder
      .token(token.value)
      .channel(channel.value)
      .build
    logger.debug(s"Sending ConversationsInfoRequest $request")
    BoltFuture { slackMethods.conversationsInfo(request) } map { response =>
      {
        logger.debug(s"Received ConversationsInfoResponse $response")
        SlackConversationInfo(channel, response.getChannel.isPrivate)
      }
    }
  }

  override def conversationsMembers(
      token: SlackAuthToken,
      channel: SlackChannelId
  ): Future[Set[SlackTeamId]] = {
    val request = ConversationsMembersRequest.builder
      .token(token.value)
      .channel(channel.value)
      .build()
    logger.debug(s"Sending ConversationsMembersRequest $request")
    BoltFuture { slackMethods.conversationsMembers(request) } map { response =>
      {
        logger.debug(s"Received ConversationsMembersResponse $response")
        response.getMembers.asScala.toSet.map(SlackTeamId)
      }
    }
  }

}
