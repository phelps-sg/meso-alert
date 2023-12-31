package dao

import com.google.inject.{Inject, Singleton}
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}
import util.FutureInitialisingComponent

import java.net.URI
import scala.concurrent.{ExecutionContext, Future}

//noinspection TypeAnnotation
@Singleton
class SlickWebhookDao @Inject() (
    val db: Database,
    val databaseExecutionContext: DatabaseExecutionContext
)(implicit val ec: ExecutionContext)
    extends WebhookDao
    with SlickHookDao[URI, Webhook, Webhook]
    with FutureInitialisingComponent {

  initialise()

  override def table: TableQuery[Tables.Webhooks] = Tables.webhooks
  override val lookupValueQuery
      : Webhook => Query[Tables.Webhooks, Webhook, Seq] = (hook: Webhook) =>
    Tables.webhooks.filter(_.url === hook.uri.toString)
  override val lookupKeyQuery: URI => Query[Tables.Webhooks, Webhook, Seq] =
    (uri: URI) => Tables.webhooks.filter(_.url === uri.toString)

  override def toKeys(results: Future[Seq[String]]): Future[Seq[URI]] =
    results map {
      _.map(new URI(_))
    }

  def allKeys(): Future[Seq[URI]] = {
    runKeyQuery(for (hook <- Tables.webhooks) yield hook.url)
  }

  def allRunningKeys(): Future[Seq[URI]] = {
    runKeyQuery(for (hook <- Tables.webhooks if hook.is_running) yield hook.url)
  }

  override protected def toDB(hook: Webhook): Future[Webhook] =
    Future.successful { hook }
  override protected def fromDB(hook: Webhook): Future[Webhook] =
    Future.successful {
      hook
    }
}
