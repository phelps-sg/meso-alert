package dao

import actors.TxUpdate
import com.google.inject.{ImplementedBy, Inject}
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}

import scala.concurrent.Future

@ImplementedBy(classOf[SlickTransactionUpdateDao])
trait TransactionUpdateDao {
  def record(txUpdate: TxUpdate): Future[Int]
  def init(): Future[Unit]
}

class SlickTransactionUpdateDao @Inject()(val db: Database,
                                            val databaseExecutionContext: DatabaseExecutionContext)
    extends TransactionUpdateDao with SlickDao[TransactionUpdate] {

  override val table = Tables.transactionUpdates

  override def init(): Future[Unit] = db.run(table.schema.create)


  def record(txUpdate: TxUpdate): Future[Int] = {
    val slickTxUpdate = TransactionUpdate(None, txUpdate.hash, txUpdate.value, txUpdate.time, txUpdate.isPending)
    db.run(Tables.transactionUpdates += slickTxUpdate)
  }

}
