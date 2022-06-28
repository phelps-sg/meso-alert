package dao
import com.google.inject.{ImplementedBy, Inject}
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}

import scala.concurrent.Future

@ImplementedBy(classOf[SlickSlashCommandHistoryDao])
trait SlashCommandHistoryDao {
  def record(slashCommand: SlashCommand): Future[Int]
  def init(): Future[Unit]
}

class SlickSlashCommandHistoryDao @Inject()(val db: Database,
                                            val databaseExecutionContext: DatabaseExecutionContext)
    extends SlashCommandHistoryDao with SlickDao[SlashCommand] {

  override val table = Tables.slashCommandHistory

  def record(slashCommand: SlashCommand): Future[Int] = {
    db.run(Tables.slashCommandHistory += slashCommand)
  }

}
