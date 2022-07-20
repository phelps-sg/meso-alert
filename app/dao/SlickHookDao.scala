package dao

import play.api.Logging
import slick.BtcPostgresProfile.api._
import slick.DatabaseExecutionContext

import scala.concurrent.Future

case class DuplicateHookException[X](uri: X) extends Exception(s"A hook already exists with key $uri")
case class SchemaConstraintViolation(message: String) extends Exception(message)

trait SlickHookDao[X, Y <: Hook[X], Z]
  extends SlickDao[Z]
  with SlickPrimaryKeyDao[X, Y, Z]
  with Logging {

  val databaseExecutionContext: DatabaseExecutionContext

  protected def toKeys(results: Future[Seq[String]]): Future[Seq[X]]

  protected def runKeyQuery(query: Query[Rep[String], String, Seq]): Future[Seq[X]] = {
    toKeys(db.run(query.result))
  }

}
