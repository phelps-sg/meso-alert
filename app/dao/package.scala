import com.google.inject.{ImplementedBy, Inject, Singleton}
import org.slf4j.{Logger, LoggerFactory}
import slick.BtcPostgresProfile.api._
import slick.dbio.Effect
import slick.{BtcPostgresProfile, DatabaseExecutionContext, Tables}
import slick.jdbc.JdbcBackend.Database
import slick.lifted.AbstractTable
import slick.sql.FixedSqlAction

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

  trait SlickHookDao[X, Y] {
    val db: Database
    val databaseExecutionContext: DatabaseExecutionContext
    val logger: Logger
    implicit val ec: DatabaseExecutionContext = databaseExecutionContext

    val table: TableQuery[_]
    val lookupHookQuery: Y => Query[_, Y, Seq]
    val lookupKeyQuery: X => Query[_, Y, Seq]
    val insertHookQuery: Y => FixedSqlAction[Int, NoStream, Effect.Write]

    def find(uri: X): Future[Option[Y]] = {
      logger.debug(s"Querying for ${uri.toString}")
      db.run(lookupKeyQuery(uri).result).map {
        case Seq(result) => Some(result)
        case Seq() => None
        case _ =>
          throw new RuntimeException(s"Multiple results returned for uri ${uri.toString}")
      }
    }

    def insert(hook: Y): Future[Int] = {
      for {
        n: Int <- db.run(lookupHookQuery(hook).size.result)
        result <-
          if (n > 0) {
            throw DuplicateHookException(hook)
          } else {
            db.run(insertHookQuery(hook))
          }
      } yield result
    }

  }

  //noinspection TypeAnnotation
  @Singleton
  class SlickWebhookDao @Inject() (val db: Database,
                                   val databaseExecutionContext: DatabaseExecutionContext)
    extends WebhookDao with SlickHookDao[URI, Webhook] {

    override val logger: Logger = LoggerFactory.getLogger(classOf[SlickWebhookDao])
    override val table = Tables.webhooks
    override val lookupHookQuery = (hook: Webhook) => Tables.webhooks.filter(_.url === hook.uri.toString)
    override val lookupKeyQuery = (uri: URI) => Tables.webhooks.filter(_.url === uri.toString)
    override val insertHookQuery = (hook: Webhook) => Tables.webhooks += hook

    def init(): Future[Unit] = db.run(Tables.webhooks.schema.createIfNotExists)
    def all(): Future[Seq[Webhook]] = db.run(Tables.webhooks.result)
    def allKeys(): Future[Seq[URI]] = db.run(Tables.webhooks.map(_.url).result) map {
      _.map(new URI(_))
    }
  }

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

    def init(): Future[Unit] = db.run(Tables.slackChatHooks.schema.createIfNotExists)
    def all(): Future[Seq[SlackChatHook]] = db.run(Tables.slackChatHooks.result)
    def allKeys(): Future[Seq[SlackChannel]] = db.run(Tables.slackChatHooks.map(_.channel_id).result) map {
      _.map(SlackChannel)
    }

  }
}
