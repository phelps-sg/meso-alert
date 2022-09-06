package slick

// scalafix:off

import actors.EncryptionActor.Encrypted
import dao._

import java.net.URI

object Tables {

  val profile: BtcPostgresProfile.type = BtcPostgresProfile
  import profile.api._

  def encodeBase64(bytes: Array[Byte]): String =
    java.util.Base64.getEncoder.encode(bytes).map(_.toChar).mkString

  def decodeBase64(data: String): Array[Byte] =
    java.util.Base64.getDecoder.decode(data)

  def ws(hook: Webhook): Option[(String, Long)] = {
    Some((hook.uri.toURL.toString, hook.threshold))
  }

  def toWebhook(tuple: (String, Long, Boolean)): Webhook =
    Webhook(new URI(tuple._1), tuple._2, tuple._3)

  class Webhooks(tag: Tag) extends Table[Webhook](tag, "webhooks") {
    def url = column[String]("url", O.PrimaryKey)
    def threshold = column[Long]("threshold")
    def is_running = column[Boolean]("is_running")
    def * = (url, threshold, is_running) <> (
      h => Webhook(new URI(h._1), h._2, h._3),
      (h: Webhook) => {
        Some(h.uri.toString, h.threshold, h.isRunning)
      }
    )
  }
  val webhooks = TableQuery[Webhooks]

  //  private val coreAttributes = List("channel_id", "command", "text")
  //  private val optionalAttributes = List("team_domain", "team_id", "channel_name",
  //    "user_id", "user_name", "is_enterprise_install")
  class SlashCommandHistory(tag: Tag)
      extends Table[SlashCommand](tag, "slack_slash_command_history") {
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def channel_id = column[SlackChannelId]("channel_id")
    def command = column[String]("command")
    def text = column[String]("text")
    def team_domain = column[Option[String]]("team_domain")
    def team_id = column[SlackTeamId]("team_id")
    def channel_name = column[Option[String]]("channel_name")
    def user_id = column[Option[SlackUserId]]("user_id")
    def user_name = column[Option[String]]("user_name")
    def is_enterprise_install = column[Option[Boolean]]("is_enterprise_install")
    def time_stamp = column[Option[java.time.LocalDateTime]]("time_stamp")

    override def * =
      (
        id.?,
        channel_id,
        command,
        text,
        team_domain,
        team_id,
        channel_name,
        user_id,
        user_name,
        is_enterprise_install,
        time_stamp
      ) <> (SlashCommand.tupled, SlashCommand.unapply)
  }
  val slashCommandHistory = TableQuery[SlashCommandHistory]

  class SlackTeams(tag: Tag)
      extends Table[SlackTeamEncrypted](tag, "slack_teams") {
    def team_id = column[SlackTeamId]("team_id", O.PrimaryKey)
    def user_id = column[SlackUserId]("user_id")
    def bot_id = column[SlackBotId]("bot_id")
    def nonce = column[String]("nonce")
    def access_token = column[String]("access_token")
    def team_name = column[String]("team_name")

    override def * =
      (team_id, user_id, bot_id, nonce, access_token, team_name) <> (
        team =>
          SlackTeamEncrypted(
            team._1,
            team._2,
            team._3,
            Encrypted(decodeBase64(team._4), decodeBase64(team._5)),
            team._6
          ),
        (team: SlackTeamEncrypted) =>
          Some(
            team.teamId,
            team.userId,
            team.botId,
            encodeBase64(team.accessToken.nonce),
            encodeBase64(team.accessToken.cipherText),
            team.teamName
          )
      )
  }
  val slackTeams = TableQuery[SlackTeams]

  class SlackChatHooks(tag: Tag)
      extends Table[SlackChatHookEncrypted](tag, "slack_chat_hooks") {
    def channel_id = column[String]("channel_id", O.PrimaryKey)
    def nonce = column[String]("nonce")
    def token = column[String]("token")
    def threshold = column[Long]("threshold")
    def is_running = column[Boolean]("is_running")
    def * = (channel_id, nonce, token, threshold, is_running) <> (
      h =>
        SlackChatHookEncrypted(
          SlackChannelId(h._1),
          Encrypted(decodeBase64(h._2), decodeBase64(h._3)),
          h._4,
          h._5
        ),
      (h: SlackChatHookEncrypted) => {
        Some(
          h.channel.id,
          encodeBase64(h.token.nonce),
          encodeBase64(h.token.cipherText),
          h.threshold,
          h.isRunning
        )
      }
    )
  }
  val slackChatHooks = TableQuery[SlackChatHooks]

  class TransactionUpdates(tag: Tag)
      extends Table[TransactionUpdate](tag, "transaction_updates") {
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def hash = column[String]("hash")
    def value = column[Long]("value")
    def time_stamp = column[java.time.LocalDateTime]("time_stamp")
    def isPending = column[Boolean]("isPending")

    override def * = (
      id.?,
      hash,
      value,
      time_stamp,
      isPending
    ) <> (TransactionUpdate.tupled, TransactionUpdate.unapply)
  }
  val transactionUpdates = TableQuery[TransactionUpdates]

  val schema =
    webhooks.schema ++ slackChatHooks.schema ++ slashCommandHistory.schema ++ slackTeams.schema ++ transactionUpdates.schema

}
