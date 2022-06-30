package dao

import com.google.inject.{ImplementedBy, Inject}
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}

import scala.concurrent.Future

@ImplementedBy(classOf[SlickSlackUserDao])
trait SlackUserDao {
  def insertOrUpdate(slackUser: SlackUser): Future[Int]
  def init(): Future[Unit]
  def find(userId: String): Future[Option[SlackUser]]
}

class SlickSlackUserDao  @Inject()(val db: Database,
                                   val databaseExecutionContext: DatabaseExecutionContext)
  extends SlickDao[SlackUser] with SlackUserDao {

  implicit val ec: DatabaseExecutionContext = databaseExecutionContext

  override val table = Tables.slackUsers

  def insertOrUpdate(slackUser: SlackUser): Future[Int] = {
    db.run(table += slackUser)
  }

  def find(userId: String): Future[Option[SlackUser]] = {
    db.run(table.filter(_.id === userId).result).map {
      case Seq(result) => Some(result)
      case Seq() => None
      case _ =>
        throw SchemaConstraintViolation(s"Multiple results returned for $userId")
    }
  }

}
