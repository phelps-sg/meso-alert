package dao

import com.google.inject.{Inject, Singleton}
import org.slf4j.{Logger, LoggerFactory}
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}

import scala.concurrent.Future

//noinspection TypeAnnotation
@Singleton
class SlickSlackChatDao @Inject() (val db: Database,
                                   val databaseExecutionContext: DatabaseExecutionContext)
  extends SlackChatHookDao with SlickHookDao[SlackChannel, SlackChatHook] {

  override val logger: Logger = LoggerFactory.getLogger(classOf[SlickWebhookDao])
  override val table = Tables.slackChatHooks
  override val lookupHookQuery =
    (hook: SlackChatHook) => Tables.slackChatHooks.filter(_.channel_id === hook.channel.id)
  override val lookupKeyQuery =
    (channel: SlackChannel) => Tables.slackChatHooks.filter(_.channel_id === channel.id)
  override val insertHookQuery = (hook: SlackChatHook) => Tables.slackChatHooks += hook
  override val insertOrUpdateHookQuery = (newHook: SlackChatHook) => Tables.slackChatHooks.insertOrUpdate(newHook)

  def init(): Future[Unit] = db.run(Tables.slackChatHooks.schema.createIfNotExists)
  def all(): Future[Seq[SlackChatHook]] = db.run(Tables.slackChatHooks.result)
  def allKeys(): Future[Seq[SlackChannel]] = db.run(Tables.slackChatHooks.map(_.channel_id).result) map {
    _.map(SlackChannel)
  }

}
