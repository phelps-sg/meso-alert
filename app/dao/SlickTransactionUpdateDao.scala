package dao

import actors.TxUpdate
import com.google.inject.{ImplementedBy, Inject}
import play.api.Logging
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}
import util.FutureInitialisingComponent

import scala.concurrent.{ExecutionContext, Future}

@ImplementedBy(classOf[SlickTransactionUpdateDao])
trait TransactionUpdateDao {
  def record(txUpdate: TxUpdate): Future[Int]
}

class SlickTransactionUpdateDao @Inject() (
    val db: Database,
    val databaseExecutionContext: DatabaseExecutionContext
)(implicit val ec: ExecutionContext)
    extends TransactionUpdateDao
    with Logging
    with SlickDao[TransactionUpdate]
    with FutureInitialisingComponent {

  initialise()

  override def table: TableQuery[Tables.TransactionUpdates] =
    Tables.transactionUpdates

  def record(txUpdate: TxUpdate): Future[Int] = {
    val slickTxUpdate = TransactionUpdate(
      None,
      txUpdate.hash,
      txUpdate.amount,
      txUpdate.time,
      txUpdate.isPending
    )
    db.run(Tables.transactionUpdates += slickTxUpdate)
  }

}
