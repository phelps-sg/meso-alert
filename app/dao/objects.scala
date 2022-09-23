package dao

import actors.EncryptionActor.Encrypted
import actors.TxHash
import slick.lifted.MappedTo
import util.Encodings.base64Encode

import java.net.URI

final case class RegisteredUserId(value: String)
    extends AnyVal
    with MappedTo[String]

final case class SlackTeamId(value: String) extends AnyVal with MappedTo[String]

final case class SlackChannelId(value: String)
    extends AnyVal
    with MappedTo[String]

final case class SlackUserId(value: String) extends AnyVal with MappedTo[String]

final case class SlackBotId(value: String) extends AnyVal with MappedTo[String]

final case class Secret(data: Array[Byte]) {
  override def toString: String = base64Encode(data)
}

final case class SlashCommand(
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

trait Hook[+X] extends ThresholdFilter {
  def key: X
  val isRunning: Boolean
  def newStatus(isRunning: Boolean): Hook[X]
}

final case class Webhook(uri: URI, threshold: Long, isRunning: Boolean)
    extends Hook[URI] {
  def key: URI = uri
  override def newStatus(isRunning: Boolean): Hook[URI] =
    copy(isRunning = isRunning)
}

final case class SlackChatHookEncrypted(
    channel: SlackChannelId,
    token: Encrypted,
    threshold: Long,
    isRunning: Boolean
)

final case class SlackChatHook(
    channel: SlackChannelId,
    token: String,
    threshold: Long,
    isRunning: Boolean
) extends Hook[SlackChannelId] {
  def key: SlackChannelId = channel
  override def newStatus(isRunning: Boolean): Hook[SlackChannelId] =
    copy(isRunning = isRunning)
}

final case class SlackTeam(
    teamId: SlackTeamId,
    userId: SlackUserId,
    botId: SlackBotId,
    accessToken: String,
    teamName: String,
    registeredUserId: RegisteredUserId
)

final case class SlackTeamEncrypted(
    teamId: SlackTeamId,
    userId: SlackUserId,
    botId: SlackBotId,
    accessToken: Encrypted,
    teamName: String,
    registeredUserId: RegisteredUserId
)

final case class TransactionUpdate(
    id: Option[Long],
    hash: TxHash,
    value: Long,
    timeStamp: java.time.LocalDateTime,
    isPending: Boolean
)
