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

final case class SlackAuthToken(value: String)
    extends AnyVal
    with MappedTo[String]

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

trait SlackChatHook extends Hook[SlackChannelId] {
  val channel: SlackChannelId
  def key: SlackChannelId = channel
}

final case class SlackChatHookEncrypted(
    channel: SlackChannelId,
    token: Encrypted,
    threshold: Long,
    isRunning: Boolean
) extends SlackChatHook {
  override def newStatus(isRunning: Boolean): SlackChatHookEncrypted =
    copy(isRunning = isRunning)
}

final case class SlackChatHookPlainText(
    channel: SlackChannelId,
    token: SlackAuthToken,
    threshold: Long,
    isRunning: Boolean
) extends SlackChatHook {
  override def newStatus(isRunning: Boolean): SlackChatHookPlainText =
    copy(isRunning = isRunning)
  override def toString: String =
    s"${getClass().getSimpleName}($channel,<redacted>,$threshold,$isRunning)"
}

final case class SlackTeam(
    teamId: SlackTeamId,
    userId: SlackUserId,
    botId: SlackBotId,
    accessToken: SlackAuthToken,
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

final case class Satoshi(value: Long) extends AnyVal with MappedTo[Long]

final case class TransactionUpdate(
    id: Option[Long],
    hash: TxHash,
    amount: Satoshi,
    timeStamp: java.time.LocalDateTime,
    isPending: Boolean
)
