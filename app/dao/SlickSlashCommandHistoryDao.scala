package dao

import com.google.inject.{ImplementedBy, Inject}
import play.api.Logging
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}
import util.FutureInitialisingComponent

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[SlickSlashCommandHistoryDao])
trait SlashCommandHistoryDao {
  def record(slashCommand: SlashCommand): Future[Int]
}

class SlickSlashCommandHistoryDao @Inject()(val db: Database,
                                            val databaseExecutionContext: DatabaseExecutionContext)
                                           (implicit val ec: ExecutionContext)
    extends SlashCommandHistoryDao with FutureInitialisingComponent with Logging with SlickDao[SlashCommand] {

  initialise()

  override def table: TableQuery[Tables.SlashCommandHistory] = Tables.slashCommandHistory

  def record(slashCommand: SlashCommand): Future[Int] = {
    db.run(Tables.slashCommandHistory += slashCommand)
  }

}
