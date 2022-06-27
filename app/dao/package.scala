import actors.TxUpdate
import com.google.inject.ImplementedBy
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.lifted.TableQuery

import java.net.URI
import scala.concurrent.Future

package object dao {

  trait Filter {
    def filter(tx: TxUpdate): Boolean
  }

  trait ThresholdFilter extends Filter {
    val threshold: Long
    def filter(tx: TxUpdate): Boolean = tx.value >= threshold
  }

  trait SlickDao[Y] {
    val table: TableQuery[_ <: Table[Y]]
    val db: Database

    def init(): Future[Unit] = db.run(table.schema.createIfNotExists)
    def all(): Future[Seq[Y]] = db.run(table.result)
  }

  trait HookDao[X, Y <: Hook[X]] {
    def init(): Future[Unit]
    def all(): Future[Seq[Hook[X]]]
    def allKeys(): Future[Seq[_ <: X]]
    def find(key: X): Future[Option[_ <: Hook[X]]]
    def insert(hook: Y): Future[Int]
    def update(hook: Y): Future[Int]
  }

  case class SlashCommand(id: Option[Int], channelId: String, command: String, text: String,
                          team_domain: Option[String], teamId: Option[String], channelName: Option[String],
                          userId: Option[String], userName: Option[String], isEnterpriseInstall: Option[Boolean],
                          timeStamp: Option[java.time.LocalDateTime])

  case class SlackChannel(id: String)

  trait Hook[+X] {
    def key: X
  }

  case class Webhook(uri: URI, threshold: Long) extends Hook[URI] with ThresholdFilter {
    def key: URI = uri
  }

  case class SlackChatHook(channel: SlackChannel, threshold: Long) extends Hook[SlackChannel] with ThresholdFilter {
    def key: SlackChannel = channel
  }

  case class DuplicateHookException[X](uri: X) extends Exception(s"A hook already exists with key $uri")

  @ImplementedBy(classOf[SlickWebhookDao])
  trait WebhookDao extends HookDao[URI, Webhook]

  @ImplementedBy(classOf[SlickSlackChatDao])
  trait SlackChatHookDao extends HookDao[SlackChannel, SlackChatHook]

  @ImplementedBy(classOf[SlickSlashCommandHistoryDao])
  trait SlashCommandHistoryDao {
    def record(slashCommand: SlashCommand): Future[Int]
    def init(): Future[Unit]
  }
}
