package dao

import com.google.inject.{ImplementedBy, Inject}
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}

import scala.concurrent.Future

@ImplementedBy(classOf[SlickSlackTeamDao])
trait SlackTeamDao {
  def insertOrUpdate(slackUser: SlackTeam): Future[Int]
  def init(): Future[Unit]
  def find(userId: String): Future[Option[SlackTeam]]
}

class SlickSlackTeamDao  @Inject()(val db: Database,
                                   val databaseExecutionContext: DatabaseExecutionContext)
  extends SlickDao[SlackTeam] with SlackTeamDao {

  implicit val ec: DatabaseExecutionContext = databaseExecutionContext

  override val table = Tables.slackTeams

  def insertOrUpdate(slackUser: SlackTeam): Future[Int] = {
    db.run(table += slackUser)
  }

  def find(teamId: String): Future[Option[SlackTeam]] = {
    db.run(table.filter(_.team_id === teamId).result).map {
      case Seq(result) => Some(result)
      case Seq() => None
      case _ =>
        throw SchemaConstraintViolation(s"Multiple results returned for $teamId")
    }
  }

}
