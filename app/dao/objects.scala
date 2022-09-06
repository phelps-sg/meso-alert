package dao

import actors.EncryptionActor.Encrypted
import slick.lifted.MappedTo
import util.Encodings.base64Encode

import java.net.URI

case class RegisteredUserId(id: String) extends AnyVal with MappedTo[String] {
  override def value: String = id
}

case class SlackTeamId(id: String) extends AnyVal with MappedTo[String] {
  override def value: String = id
}

case class SlackChannelId(id: String) extends AnyVal with MappedTo[String] {
  override def value: String = id
}

case class SlackUserId(id: String) extends AnyVal with MappedTo[String] {
  override def value: String = id
}

case class SlackBotId(id: String) extends AnyVal with MappedTo[String] {
  override def value: String = id
}

case class Secret(data: Array[Byte]) {
  override def toString: String = base64Encode(data)
}

case class SlashCommand(
    id: Option[Int],
    channelId: SlackChannelId,
    command: String,
    text: String,
    teamDomain: Option[String],
    teamId: SlackTeamId,
    channelName: Option[String],
    userId: Option[SlackUserId],
    userName: Option[String],
    isEnterpriseInstall: Option[Boolean],
    timeStamp: Option[java.time.LocalDateTime]
)

//case class SlackChannel(id: String)

trait Hook[+X] extends ThresholdFilter {
  def key: X
  val isRunning: Boolean
  def newStatus(isRunning: Boolean): Hook[X]
}

case class Webhook(uri: URI, threshold: Long, isRunning: Boolean)
    extends Hook[URI] {
  def key: URI = uri
  override def newStatus(isRunning: Boolean): Hook[URI] =
    copy(isRunning = isRunning)
}

case class SlackChatHookEncrypted(
    channel: SlackChannelId,
    token: Encrypted,
    threshold: Long,
    isRunning: Boolean
)

case class SlackChatHook(
    channel: SlackChannelId,
    token: String,
    threshold: Long,
    isRunning: Boolean
) extends Hook[SlackChannelId] {
  def key: SlackChannelId = channel
  override def newStatus(isRunning: Boolean): Hook[SlackChannelId] =
    copy(isRunning = isRunning)
}

case class SlackTeam(
    teamId: SlackTeamId,
    userId: SlackUserId,
    botId: SlackBotId,
    accessToken: String,
    teamName: String
)
case class SlackTeamEncrypted(
    teamId: SlackTeamId,
    userId: SlackUserId,
    botId: SlackBotId,
    accessToken: Encrypted,
    teamName: String
)

case class TransactionUpdate(
    id: Option[Long],
    hash: String,
    value: Long,
    timeStamp: java.time.LocalDateTime,
    isPending: Boolean
)
