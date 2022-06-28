package dao

import org.slf4j.Logger
import slick.BtcPostgresProfile.api._
import slick.DatabaseExecutionContext

import scala.concurrent.Future

case class DuplicateHookException[X](uri: X) extends Exception(s"A hook already exists with key $uri")

trait SlickHookDao[X, Y <: Hook[X]] extends SlickDao[Y] {

  val logger: Logger
  val databaseExecutionContext: DatabaseExecutionContext
  val lookupHookQuery: Y => Query[_, Y, Seq]
  val lookupKeyQuery: X => Query[_, Y, Seq]

  implicit val ec: DatabaseExecutionContext = databaseExecutionContext

  def find(key: X): Future[Option[Hook[X]]] = {
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
          db.run(table += hook)
        }
    } yield result
  }

  def update(hook: Y): Future[Int] = {
    for {
      result <-
        db.run(table.insertOrUpdate(hook))
    } yield result
  }

}
