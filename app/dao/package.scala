import com.google.inject.{ImplementedBy, Inject, Singleton}
import org.slf4j.{Logger, LoggerFactory}
import slick.BtcPostgresProfile.api._
import slick.{DatabaseExecutionContext, Tables}
import slick.jdbc.JdbcBackend.Database

import java.net.URI
import scala.concurrent.Future

package object dao {

  case class Webhook(uri: URI, threshold: Long)
  case class DuplicateWebhookException(uri: URI) extends Exception(s"A webhook already exists with uri $uri")

  @ImplementedBy(classOf[SlickWebhookDao])
  trait WebhookDao {
    def init(): Future[Unit]
    def all(): Future[Seq[Webhook]]
    def forUri(uri: URI): Future[Option[Webhook]]
    def insert(hook: Webhook): Future[Int]
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

    def forUri(uri: URI): Future[Option[Webhook]] = {
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
