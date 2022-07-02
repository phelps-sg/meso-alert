package dao

import com.google.inject.{ImplementedBy, Inject}
import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.{DatabaseExecutionContext, Tables}
import actors.TxUpdate


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

  def record(txUpdate: TxUpdate): Future[Int] = {
    val timeString = txUpdate.time.toString()
    val slickTxUpdate = TransactionUpdate(Some(0L), txUpdate.hash, txUpdate.value, timeString, txUpdate.isPending)
    db.run(Tables.transactionUpdates += slickTxUpdate)
  }

}
