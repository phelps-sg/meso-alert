package dao

import java.net.URI

case class SlashCommand(id: Option[Int], channelId: String, command: String, text: String,
                        teamDomain: Option[String], teamId: Option[String], channelName: Option[String],
                        userId: Option[String], userName: Option[String], isEnterpriseInstall: Option[Boolean],
                        timeStamp: Option[java.time.LocalDateTime])

case class SlackChannel(id: String)

trait Hook[+X] {
  def key: X
  val isRunning: Boolean
  def newStatus(isRunning: Boolean): Hook[X]
}

case class Webhook(uri: URI, threshold: Long, isRunning: Boolean) extends Hook[URI] with ThresholdFilter {
  def key: URI = uri
  override def newStatus(isRunning: Boolean): Hook[URI] = copy(isRunning = isRunning)
}

case class SlackChatHook(channel: SlackChannel, threshold: Long, isRunning: Boolean) extends Hook[SlackChannel] with ThresholdFilter {
  def key: SlackChannel = channel
  override def newStatus(isRunning: Boolean): Hook[SlackChannel] = copy(isRunning = isRunning)
}

case class SlackUser(id: String, botId: String, accessToken: String, teamId: String, teamName: String)
