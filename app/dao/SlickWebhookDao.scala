package dao

import com.google.inject.{Inject, Singleton}
import org.slf4j.{Logger, LoggerFactory}
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}

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

  override def toKeys(results: Future[Seq[String]]): Future[Seq[URI]] = results map {
    _.map(new URI(_))
  }

  def allKeys(): Future[Seq[URI]] = {
    runKeyQuery(for (hook <- Tables.webhooks) yield hook.url)
  }

  def allRunningKeys(): Future[Seq[URI]] = {
    runKeyQuery(for (hook <- Tables.webhooks if hook.is_running) yield hook.url)
  }

}
