package dao


import play.api.Logging
import slick.BtcPostgresProfile.api._
import slick.DatabaseExecutionContext
import slick.jdbc.JdbcBackend.Database
import slick.lifted.TableQuery

import scala.concurrent.Future

/**
 * Mixin for DAO classes that store a case class (value) in a Slick database table with
 * a primary key.  The mixin allows two different types of value: the type in the DAO interface
 * versus the type stored in the Slick table, with translation performed using the
 * `toDB` and `fromDB` methods.  The typical use-case for the latter is to allow
 * e.g. `User` to be used in the DAO methods, whereas `UserEncrypted` is stored in the database
 * with an encrypted password field.  If this functionality is not required then `Y` and `Z`
 * can be the same type, and `toDB` and `fromDB` can simply return `Future { value }`.
 *
 * @tparam X  The type of the key
 * @tparam Y  The value type understood by the DAO
 * @tparam Z  The value type as stored in the Slick table
 */
trait SlickPrimaryKeyDao[X, Y, Z] { slickDao: SlickDao[Z] with Logging =>

  def table: TableQuery[_ <: Table[Z]]
  def db: Database
  implicit val databaseExecutionContext: DatabaseExecutionContext
  val lookupValueQuery: Y => Query[_, Z, Seq]
  val lookupKeyQuery: X => Query[_, Z, Seq]

  protected def toDB(value: Y): Future[Z]
  protected def fromDB(value: Z): Future[Y]

  def find(key: X): Future[Y] = {
    logger.debug(s"Querying for ${key.toString}")
    for {
      queryResult <- db.run(lookupKeyQuery(key).result)
      finalResult <- queryResult match {
        case Seq(x) => fromDB(x)
        case Seq() => Future.failed(new NoSuchElementException(key.toString))
        case _ => Future.failed(SchemaConstraintViolation(s"Multiple results returned for uri ${key.toString}"))
      }
    } yield finalResult
  }

  def insertOrUpdate(value: Y): Future[Int] = {
    for {
      storedValue <- toDB(value)
      result <- db.run(table.insertOrUpdate(storedValue))
    } yield result
  }

  def insert(value: Y): Future[Int] = {
    val storeHook = toDB(value)
    for {
      n: Int <- db.run(lookupValueQuery(value).size.result)
      storedHook <- storeHook
      result <-
        if (n > 0) {
          Future.failed(DuplicateKeyException(value))
        } else {
          db.run(table += storedHook)
        }
    } yield result
  }

  def update(value: Y): Future[Int] = {
    val store = toDB(value)
    for {
      storedValue <- store
      result <-
        db.run(table.insertOrUpdate(storedValue))
    } yield result
  }

}
