package dao

import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.lifted.TableQuery

import scala.concurrent.Future

trait SlickDao[Y] {
  def table: TableQuery[_ <: Table[Y]]
  def db: Database

  protected def initialiseFuture(): Future[Unit] = db.run(table.schema.createIfNotExists)
}
