package dao

import com.google.inject.{Inject, Singleton}
import org.slf4j.{Logger, LoggerFactory}
import slick.{DatabaseExecutionContext, Tables}
import slick.jdbc.JdbcBackend.Database
import slick.BtcPostgresProfile.api._

import java.net.URI
import scala.concurrent.Future

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
