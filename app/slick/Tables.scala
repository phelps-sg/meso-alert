package slick

// scalafix:off

import dao._

import java.net.URI

object Tables {

  val profile: BtcPostgresProfile.type = BtcPostgresProfile
  import profile.api._

  def ws(hook: Webhook): Option[(String, Long)] = {
    Some((hook.uri.toURL.toString, hook.threshold))
  }

  def toWebhook(tuple: (String, Long, Boolean)): Webhook = Webhook(new URI(tuple._1), tuple._2, tuple._3)

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
  class SlashCommandHistory(tag: Tag) extends Table[SlashCommand](tag, "slack_slash_command_history") {
    def id = column[Int]("id", O.PrimaryKey, O.AutoInc)
    def channel_id = column[String]("channel_id")
    def command = column[String]("command")
    def text = column[String]("text")
    def team_domain = column[Option[String]]("team_domain")
    def team_id = column[String]("team_id")
    def channel_name = column[Option[String]]("channel_name")
    def user_id = column[Option[String]]("user_id")
    def user_name = column[Option[String]]("user_name")
    def is_enterprise_install = column[Option[Boolean]]("is_enterprise_install")
    def time_stamp = column[Option[java.time.LocalDateTime]]("time_stamp")

    override def * =
      (id.?, channel_id, command, text, team_domain, team_id, channel_name, user_id,
        user_name, is_enterprise_install, time_stamp) <> (SlashCommand.tupled, SlashCommand.unapply)
  }
  val slashCommandHistory = TableQuery[SlashCommandHistory]

  class SlackTeams(tag: Tag) extends Table[SlackTeam](tag, "slack_teams") {
    def team_id = column[String]("team_id", O.PrimaryKey)
    def user_id = column[String]("user_id")
    def bot_id = column[String]("bot_id")
    def access_token = column[String]("access_token")
    def team_name = column[String]("team_name")

    override def * =
      (team_id, user_id, bot_id, access_token, team_name) <> (SlackTeam.tupled, SlackTeam.unapply)
  }
  val slackTeams = TableQuery[SlackTeams]

  class SlackChatHooks(tag: Tag) extends Table[SlackChatHook](tag, "slack_chat_hooks") {
    def channel_id = column[String]("channel_id", O.PrimaryKey)
    def token = column[String]("token")
    def threshold = column[Long]("threshold")
    def is_running = column[Boolean]("is_running")
    def * = (channel_id, token, threshold, is_running) <> (
      h => SlackChatHook(SlackChannel(h._1), h._2, h._3, h._4),
      (h: SlackChatHook) => {
        Some(h.channel.id, h.token, h.threshold, h.isRunning)
      }
    )
  }
  val slackChatHooks = TableQuery[SlackChatHooks]

  class TransactionUpdates(tag: Tag) extends Table[TransactionUpdate](tag, "transaction_updates") { 
    def id = column[Long]("id", O.PrimaryKey, O.AutoInc)
    def hash = column[String]("hash") 
    def value = column[Long]("value") 
    def time_stamp = column[java.time.LocalDateTime]("time_stamp")
    def isPending = column[Boolean]("isPending")

    override def * = (id.?, hash, value, time_stamp, isPending) <> (TransactionUpdate.tupled, TransactionUpdate.unapply) 
}
  val transactionUpdates = TableQuery[TransactionUpdates]


  val schema = webhooks.schema ++ slackChatHooks.schema ++ slashCommandHistory.schema ++ slackTeams.schema ++ transactionUpdates.schema

}
