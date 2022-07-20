package dao

import com.google.inject.{ImplementedBy, Inject}
import play.api.Logging
import services.EncryptionManagerService
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}
import util.FutureInitialisingComponent

import scala.concurrent.Future

@ImplementedBy(classOf[SlickSlackTeamDao])
trait SlackTeamDao {
  def insertOrUpdate(slackUser: SlackTeam): Future[Int]
  def find(userId: String): Future[SlackTeam]
}

class SlickSlackTeamDao  @Inject()(val db: Database,
                                   val databaseExecutionContext: DatabaseExecutionContext,
                                   val encryptionManager: EncryptionManagerService)
  extends FutureInitialisingComponent with SlickDao[SlackTeamEncrypted] with Logging with SlackTeamDao {

  implicit val ec: DatabaseExecutionContext = databaseExecutionContext
  override def table: TableQuery[Tables.SlackTeams] = Tables.slackTeams

  initialise()

  def insertOrUpdate(slackUser: SlackTeam): Future[Int] = {
    for {
      encrypted <- toDB(slackUser)
      result <- db.run(table += encrypted)
    } yield result
  }

  def find(teamId: String): Future[SlackTeam] = {
    for {
      queryResult: Seq[SlackTeamEncrypted] <- db.run(table.filter(_.team_id === teamId).result)
      teamEncrypted <- queryResult match {
        case Seq(x) => fromDB(x)
        case Seq() => Future.failed(new NoSuchElementException(teamId))
        case _ => Future.failed(SchemaConstraintViolation(s"Multiple results returned for uri $teamId"))
      }
    } yield teamEncrypted
  }

  def fromDB(team: SlackTeamEncrypted): Future[SlackTeam] = {
    encryptionManager.decrypt(team.accessToken) map {
      decrypted =>
        SlackTeam(team.teamId, team.userId, team.botId, decrypted.asString, team.teamName)
    }
  }

  def toDB(team: SlackTeam): Future[SlackTeamEncrypted] = {
    encryptionManager.encrypt(team.accessToken.getBytes) map {
      encrypted =>
        SlackTeamEncrypted(team.teamId, team.userId, team.botId, accessToken = encrypted, team.teamName)
    }
  }

}
