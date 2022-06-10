import com.google.inject.ImplementedBy
import org.slf4j.Logger
import slick.BtcPostgresProfile.api._
import slick.DatabaseExecutionContext
import slick.dbio.Effect
import slick.jdbc.JdbcBackend.Database
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

}
