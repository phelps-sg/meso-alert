package slack

import com.slack.api.Slack
import com.slack.api.methods.AsyncMethodsClient
import play.api.Configuration

trait SlackClient {
  protected val config: Configuration
  protected val slack: Slack = Slack.getInstance()
  protected val token: String = config.get[String]("slack.botToken")
  protected val methods: AsyncMethodsClient = slack.methodsAsync(token)
}
