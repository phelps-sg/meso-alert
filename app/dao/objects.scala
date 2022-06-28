package dao

import java.net.URI

case class SlashCommand(id: Option[Int], channelId: String, command: String, text: String,
                        team_domain: Option[String], teamId: Option[String], channelName: Option[String],
                        userId: Option[String], userName: Option[String], isEnterpriseInstall: Option[Boolean],
                        timeStamp: Option[java.time.LocalDateTime])

case class SlackChannel(id: String)

trait Hook[+X] {
  def key: X
}

case class Webhook(uri: URI, threshold: Long) extends Hook[URI] with ThresholdFilter {
  def key: URI = uri
}

case class SlackChatHook(channel: SlackChannel, threshold: Long) extends Hook[SlackChannel] with ThresholdFilter {
  def key: SlackChannel = channel
}
