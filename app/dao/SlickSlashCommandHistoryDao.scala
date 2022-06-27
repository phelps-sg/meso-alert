package dao
import slick.BtcPostgresProfile.api._
import com.google.inject.Inject
import slick.{DatabaseExecutionContext, Tables}
import slick.jdbc.JdbcBackend.Database

import scala.concurrent.Future

class SlickSlashCommandHistoryDao @Inject()(val db: Database,
                                            val databaseExecutionContext: DatabaseExecutionContext)
    extends SlashCommandHistoryDao {

  def record(slashCommand: SlashCommand): Future[Int] = {
    db.run(Tables.slashCommandHistory += slashCommand)
  }

}
