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
class SlickSlackChatDao @Inject() (val db: Database,
                                   val databaseExecutionContext: DatabaseExecutionContext,
                                   val encryptionManager: EncryptionManagerService)
                                  (implicit val ec: ExecutionContext)
  extends SlackChatHookDao with SlickHookDao[SlackChannel, SlackChatHook, SlackChatHookEncrypted]
    with FutureInitialisingComponent {

  initialise()

  override def table = Tables.slackChatHooks
  override val lookupValueQuery: SlackChatHook => Query[Tables.SlackChatHooks, SlackChatHookEncrypted, Seq] =
    (hook: SlackChatHook) => Tables.slackChatHooks.filter(_.channel_id === hook.channel.id)
  override val lookupKeyQuery: SlackChannel => Query[Tables.SlackChatHooks, SlackChatHookEncrypted, Seq] =
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

  override protected def toDB(hook: SlackChatHook): Future[SlackChatHookEncrypted] =
    encryptionManager.encrypt(hook.token.getBytes) map {
      encrypted =>
        SlackChatHookEncrypted(channel = hook.channel, token = encrypted,
                                threshold = hook.threshold, isRunning = hook.isRunning)
    }

  override protected def fromDB(hook: SlackChatHookEncrypted): Future[SlackChatHook] =
    encryptionManager.decrypt(hook.token) map {
      decrypted =>
        SlackChatHook(channel = hook.channel, token = decrypted.asString,
                        threshold = hook.threshold, isRunning = hook.isRunning)
    }
}
