package dao

import org.slf4j.Logger
import slick.DatabaseExecutionContext
import slick.dbio.Effect
import slick.jdbc.JdbcBackend.Database
import slick.BtcPostgresProfile.api._
import slick.sql.FixedSqlAction

import scala.concurrent.Future

trait SlickHookDao[X, Y] {
  val db: Database
  val databaseExecutionContext: DatabaseExecutionContext
  val logger: Logger
  implicit val ec: DatabaseExecutionContext = databaseExecutionContext

  val table: TableQuery[_]
  val lookupHookQuery: Y => Query[_, Y, Seq]
  val lookupKeyQuery: X => Query[_, Y, Seq]
  val insertHookQuery: Y => FixedSqlAction[Int, NoStream, Effect.Write]

  def find(key: X): Future[Option[Y]] = {
    logger.debug(s"Querying for ${key.toString}")
    db.run(lookupKeyQuery(key).result).map {
      case Seq(result) => Some(result)
      case Seq() => None
      case _ =>
        throw new RuntimeException(s"Multiple results returned for uri ${key.toString}")
    }
  }

  def insert(hook: Y): Future[Int] = {
    for {
      n: Int <- db.run(lookupHookQuery(hook).size.result)
      result <-
        if (n > 0) {
          throw DuplicateHookException(hook)
        } else {
          db.run(insertHookQuery(hook))
        }
    } yield result
  }

}
