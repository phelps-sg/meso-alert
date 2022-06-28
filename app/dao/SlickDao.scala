package dao

import slick.BtcPostgresProfile.api._
import slick.jdbc.JdbcBackend.Database
import slick.lifted.TableQuery

import scala.concurrent.Future

trait SlickDao[Y] {
  val table: TableQuery[_ <: Table[Y]]
  val db: Database

  def init(): Future[Unit] = db.run(table.schema.createIfNotExists)
  def all(): Future[Seq[Y]] = db.run(table.result)
}
