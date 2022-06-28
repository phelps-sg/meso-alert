package dao

import com.google.inject.{Inject, Singleton}
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}

import scala.concurrent.Future

//noinspection TypeAnnotation
@Singleton
class SlickSlackChatDao @Inject() (val db: Database,
                                   val databaseExecutionContext: DatabaseExecutionContext)
  extends SlackChatHookDao with SlickHookDao[SlackChannel, SlackChatHook] {

  override val table = Tables.slackChatHooks
  override val lookupHookQuery =
    (hook: SlackChatHook) => Tables.slackChatHooks.filter(_.channel_id === hook.channel.id)
  override val lookupKeyQuery =
    (channel: SlackChannel) => Tables.slackChatHooks.filter(_.channel_id === channel.id)

  override def toKeys(results: Future[Seq[String]]): Future[Seq[SlackChannel]] = {
    results map {
      _.map(SlackChannel)
    }
  }

  def allKeys(): Future[Seq[SlackChannel]] = {
    runKeyQuery(for(hook <- Tables.slackChatHooks) yield hook.channel_id)
  }

  override def allRunningKeys(): Future[Seq[_ <: SlackChannel]] = {
    runKeyQuery(for(hook <- Tables.slackChatHooks if hook.is_running) yield hook.channel_id)
  }
}
