package dao

import java.net.URI

case class SlashCommand(id: Option[Int], channelId: String, command: String, text: String,
                        teamDomain: Option[String], teamId: String, channelName: Option[String],
                        userId: Option[String], userName: Option[String], isEnterpriseInstall: Option[Boolean],
                        timeStamp: Option[java.time.LocalDateTime])

case class SlackChannel(id: String)

trait Hook[+X] extends ThresholdFilter {
  def key: X
  val isRunning: Boolean
  def newStatus(isRunning: Boolean): Hook[X]
}

case class Webhook(uri: URI, threshold: Long, isRunning: Boolean) extends Hook[URI] {
  def key: URI = uri
  override def newStatus(isRunning: Boolean): Hook[URI] = copy(isRunning = isRunning)
}

case class SlackChatHook(channel: SlackChannel, token: String, threshold: Long, isRunning: Boolean) extends Hook[SlackChannel] {
  def key: SlackChannel = channel
  override def newStatus(isRunning: Boolean): Hook[SlackChannel] = copy(isRunning = isRunning)
}

case class SlackTeam(teamId: String, userId: String, botId: String, accessToken: String, teamName: String)
