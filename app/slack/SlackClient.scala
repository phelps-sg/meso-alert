package slack

import com.slack.api.Slack
import com.slack.api.methods.AsyncMethodsClient
import play.api.Configuration

trait SlackClient {
  protected val config: Configuration
  protected val slack: Slack = Slack.getInstance()
  protected val token: String = config.get[String]("slack.botToken")
  protected val slackClientId: String = config.get[String]("slack.clientId")
  protected val slackClientSecret: String = config.get[String]("slack.clientSecret")
  protected val slackMethods: AsyncMethodsClient = slack.methodsAsync(token)
}
