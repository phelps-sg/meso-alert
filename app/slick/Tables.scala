package slick

// scalafix:off

import dao.{SlackChannel, SlackChatHook, SlashCommand, Webhook}

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
    def team_id = column[Option[String]]("team_id")
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

  class SlackChatHooks(tag: Tag) extends Table[SlackChatHook](tag, "slack_chat_hooks") {
    def channel_id = column[String]("channel_id", O.PrimaryKey)
    def threshold = column[Long]("threshold")
    def is_running = column[Boolean]("is_running")
    def * = (channel_id, threshold, is_running) <> (
      h => SlackChatHook(SlackChannel(h._1), h._2, h._3),
      (h: SlackChatHook) => {
        Some(h.channel.id, h.threshold, h.isRunning)
      }
    )
  }
  val slackChatHooks = TableQuery[SlackChatHooks]

  val schema = webhooks.schema ++ slackChatHooks.schema ++ slashCommandHistory.schema

}
