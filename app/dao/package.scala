import com.google.inject.{ImplementedBy, Inject, Singleton}
import org.slf4j.{Logger, LoggerFactory}
import slick.BtcPostgresProfile.api._
import slick.{DatabaseExecutionContext, Tables}
import slick.jdbc.JdbcBackend.Database

import java.net.URI
import scala.concurrent.Future

package object dao {

  trait HookWithThreshold {
    val threshold: Long
  }

  case class SlackChannel(id: String)

  case class Webhook(uri: URI, threshold: Long) extends HookWithThreshold
  case class SlackChatHook(channel: SlackChannel, threshold: Long) extends HookWithThreshold

  case class DuplicateHookException[X](uri: X) extends Exception(s"A hook already exists with key $uri")

  trait HookDao[X, Y] {
    def init(): Future[Unit]
    def all(): Future[Seq[Y]]
    def allKeys(): Future[Seq[X]]
    def find(uri: X): Future[Option[Y]]
    def insert(hook: Y): Future[Int]
  }

  @ImplementedBy(classOf[SlickWebhookDao])
  trait WebhookDao extends HookDao[URI, Webhook]

  @ImplementedBy(classOf[SlickSlackChatDao])
  trait SlackChatHookDao extends HookDao[SlackChannel, SlackChatHook]

  @Singleton
  class SlickWebhookDao @Inject() (val db: Database,
                                   val databaseExecutionContext: DatabaseExecutionContext)
    extends WebhookDao {

    implicit val ec: DatabaseExecutionContext = databaseExecutionContext

    val logger: Logger = LoggerFactory.getLogger(classOf[SlickWebhookDao])

    def init(): Future[Unit] = db.run(Tables.webhooks.schema.createIfNotExists)

    def insert(hook: Webhook): Future[Int] = {
      for {
        n: Int <- db.run(Tables.webhooks.filter(_.url === hook.uri.toString).size.result)
        result <-
          if (n > 0) {
            throw DuplicateHookException(hook.uri)
          } else {
            db.run(Tables.webhooks += hook)
          }
      } yield result
    }

    def all(): Future[Seq[Webhook]] = db.run(Tables.webhooks.result)
    def allKeys(): Future[Seq[URI]] = db.run(Tables.webhooks.map(_.url).result) map {
      _.map(new URI(_))
    }

    def find(uri: URI): Future[Option[Webhook]] = {
      logger.debug(s"Querying for ${uri.toString}")
      db.run(Tables.webhooks.filter(_.url === uri.toString).result).map {
        case Seq(result) => Some(result)
        case Seq() => None
        case _ =>
          throw new RuntimeException(s"Multiple results returned for uri ${uri.toString}")
      }
    }

  }
  @Singleton
  class SlickSlackChatDao @Inject() (val db: Database,
                                      val databaseExecutionContext: DatabaseExecutionContext)
    extends SlackChatHookDao {

    implicit val ec: DatabaseExecutionContext = databaseExecutionContext

    val logger: Logger = LoggerFactory.getLogger(classOf[SlickWebhookDao])

    def init(): Future[Unit] = db.run(Tables.slackChatHooks.schema.createIfNotExists)

    def insert(hook: SlackChatHook): Future[Int] = {
      for {
        n: Int <- db.run(Tables.slackChatHooks.filter(_.channel_id === hook.channel.id).size.result)
        result <-
          if (n > 0) {
            throw DuplicateHookException(hook.channel)
          } else {
            db.run(Tables.slackChatHooks += hook)
          }
      } yield result
    }

    def all(): Future[Seq[SlackChatHook]] = db.run(Tables.slackChatHooks.result)
    def allKeys(): Future[Seq[SlackChannel]] = db.run(Tables.slackChatHooks.map(_.channel_id).result) map {
      _.map(SlackChannel)
    }

    def find(channel: SlackChannel): Future[Option[SlackChatHook]] = {
      db.run(Tables.slackChatHooks.filter(_.channel_id === channel.id).result).map {
        case Seq(result) => Some(result)
        case Seq() => None
        case _ =>
          throw new RuntimeException(s"Multiple results returned for channel ${channel.id}")
      }
    }

  }
}
