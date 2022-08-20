package dao

import com.google.inject.{ImplementedBy, Inject}
import play.api.Logging
import services.EncryptionManagerService
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{BtcPostgresProfile, DatabaseExecutionContext, Tables}
import util.FutureInitialisingComponent

import scala.annotation.unused
import scala.concurrent.Future

@ImplementedBy(classOf[SlickSlackTeamDao])
trait SlackTeamDao {
  def insertOrUpdate(@unused slackUser: SlackTeam): Future[Int]
  def find(@unused userId: String): Future[SlackTeam]
}

class SlickSlackTeamDao @Inject() (
    val db: Database,
    val databaseExecutionContext: DatabaseExecutionContext,
    val encryptionManager: EncryptionManagerService
) extends FutureInitialisingComponent
    with SlickDao[SlackTeamEncrypted]
    with SlickPrimaryKeyDao[String, SlackTeam, SlackTeamEncrypted]
    with Logging
    with SlackTeamDao {

  implicit val ec: DatabaseExecutionContext = databaseExecutionContext

  override val lookupValueQuery: SlackTeam => BtcPostgresProfile.api.Query[
    _,
    SlackTeamEncrypted,
    Seq
  ] = { team: SlackTeam =>
    table.filter(_.team_id === team.teamId)
  }

  override val lookupKeyQuery
      : String => BtcPostgresProfile.api.Query[_, SlackTeamEncrypted, Seq] = {
    teamId: String => table.filter(_.team_id === teamId)
  }

  override def table: TableQuery[Tables.SlackTeams] = Tables.slackTeams

  override def fromDB(team: SlackTeamEncrypted): Future[SlackTeam] = {
    encryptionManager.decrypt(team.accessToken) map { decrypted =>
      SlackTeam(
        team.teamId,
        team.userId,
        team.botId,
        decrypted.asString,
        team.teamName
      )
    }
  }

  override def toDB(team: SlackTeam): Future[SlackTeamEncrypted] = {
    encryptionManager.encrypt(team.accessToken.getBytes) map { encrypted =>
      SlackTeamEncrypted(
        team.teamId,
        team.userId,
        team.botId,
        accessToken = encrypted,
        team.teamName
      )
    }
  }

  initialise()
}
