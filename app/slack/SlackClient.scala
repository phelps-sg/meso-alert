package slack

import com.slack.api.Slack
import play.api.Configuration

trait SlackClient {
  protected val config: Configuration
  protected val slack: Slack = Slack.getInstance()
  protected val slackClientId: String = config.get[String]("slack.clientId")
  protected val slackClientSecret: String =
    config.get[String]("slack.clientSecret")
}
