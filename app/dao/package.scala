import com.google.inject.{ImplementedBy, Inject, Singleton}
import org.slf4j.{Logger, LoggerFactory}
import slick.BtcPostgresProfile.api._
import slick.{DatabaseExecutionContext, Tables}
import slick.jdbc.JdbcBackend.Database

import java.net.URI
import scala.concurrent.Future

package object dao {

  trait HasThreshold {
    val threshold: Long
  }
  case class Webhook(uri: URI, threshold: Long) extends HasThreshold
  case class SlackChannel(id: String)
  case class SlackChatHook(channel: SlackChannel, threshold: Long) extends HasThreshold
  case class DuplicateWebhookException(uri: URI) extends Exception(s"A webhook already exists with uri $uri")

  trait HookDao[X, Y] {
    def init(): Future[Unit]
    def all(): Future[Seq[Y]]
    def find(uri: X): Future[Option[Y]]
    def insert(hook: Y): Future[Int]
  }

  @ImplementedBy(classOf[SlickWebhookDao])
  trait WebhookDao extends HookDao[URI, Webhook] {
//    def init(): Future[Unit]
//    def all(): Future[Seq[Webhook]]
//    def findWebHookFor(uri: URI): Future[Option[Webhook]]
//    def insert(hook: Webhook): Future[Int]
  }

  trait SlackAlertDao extends HookDao[SlackChannel, SlackChatHook] {
//    def init(): Future[Unit]
//    def all(): Future[Seq[SlackChatHook]]
//    def findSlackAlertFor(channelId: String): Future[Option[SlackChatHook]]
//    def insertOrUpdate(alert: SlackChatHook): Future[Unit]
  }

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
            throw DuplicateWebhookException(hook.uri)
          } else {
            db.run(Tables.webhooks += hook)
          }
      } yield result
    }

    def all(): Future[Seq[Webhook]] = db.run(Tables.webhooks.result)

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

}
