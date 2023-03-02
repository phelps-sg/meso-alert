package dao

import com.google.inject.{Inject, Singleton}
import services.EncryptionManagerService
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}
import util.FutureInitialisingComponent

import scala.concurrent.{ExecutionContext, Future}

//noinspection TypeAnnotation
@Singleton
class SlickSlackChatDao @Inject() (
    val db: Database,
    val databaseExecutionContext: DatabaseExecutionContext,
    val encryptionManager: EncryptionManagerService
)(implicit val ec: ExecutionContext)
    extends SlackChatHookDao
    with SlickHookDao[
      SlackChannelId,
      SlackChatHookPlainText,
      SlackChatHookEncrypted
    ]
    with FutureInitialisingComponent {

  initialise()

  override def table = Tables.slackChatHooks
  override val lookupValueQuery: SlackChatHookPlainText => Query[
    Tables.SlackChatHooks,
    SlackChatHookEncrypted,
    Seq
  ] =
    (hook: SlackChatHookPlainText) =>
      Tables.slackChatHooks.filter(_.channel_id === hook.channel.value)
  override val lookupKeyQuery: SlackChannelId => Query[
    Tables.SlackChatHooks,
    SlackChatHookEncrypted,
    Seq
  ] =
    (channel: SlackChannelId) =>
      Tables.slackChatHooks.filter(_.channel_id === channel.value)

  override def toKeys(
      results: Future[Seq[String]]
  ): Future[Seq[SlackChannelId]] = {
    results map {
      _.map(SlackChannelId)
    }
  }

  def allKeys(): Future[Seq[SlackChannelId]] = {
    runKeyQuery(for (hook <- Tables.slackChatHooks) yield hook.channel_id)
  }

  override def allRunningKeys(): Future[Seq[_ <: SlackChannelId]] = {
    runKeyQuery(
      for (hook <- Tables.slackChatHooks if hook.is_running)
        yield hook.channel_id
    )
  }

  override protected def toDB(
      hook: SlackChatHookPlainText
  ): Future[SlackChatHookEncrypted] =
    encryptionManager.encrypt(hook.token.value.getBytes) map { encrypted =>
      SlackChatHookEncrypted(
        channel = hook.channel,
        token = encrypted,
        threshold = hook.threshold,
        isRunning = hook.isRunning
      )
    }

  override protected def fromDB(
      hook: SlackChatHookEncrypted
  ): Future[SlackChatHookPlainText] =
    encryptionManager.decrypt(hook.token) map { decrypted =>
      SlackChatHookPlainText(
        channel = hook.channel,
        token = SlackAuthToken(decrypted.asString),
        threshold = hook.threshold,
        isRunning = hook.isRunning
      )
    }
}
