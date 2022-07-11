package dao

import play.api.Logging
import slick.BtcPostgresProfile.api._
import slick.DatabaseExecutionContext

import scala.concurrent.Future

case class DuplicateHookException[X](uri: X) extends Exception(s"A hook already exists with key $uri")
case class SchemaConstraintViolation(message: String) extends Exception(message)
case object NoResultException extends Exception("no result")

trait SlickHookDao[X, Y <: Hook[X], Z] extends SlickDao[Z] with Logging {

  val databaseExecutionContext: DatabaseExecutionContext
  val lookupHookQuery: Y => Query[_, Z, Seq]
  val lookupKeyQuery: X => Query[_, Z, Seq]

  implicit val ec: DatabaseExecutionContext = databaseExecutionContext

  protected def toDB(hook: Y): Future[Z]
  protected def fromDB(hook: Z): Future[Y]

  protected def toKeys(results: Future[Seq[String]]): Future[Seq[X]]

  def find(key: X): Future[Option[Hook[X]]] = {
    logger.debug(s"Querying for ${key.toString}")
    (for {
      queryResult <- db.run(lookupKeyQuery(key).result)
      finalResult <- queryResult match {
        case Seq(x) => fromDB(x)
        case Seq() => Future.failed(NoResultException)
        case _ => Future.failed(SchemaConstraintViolation(s"Multiple results returned for uri ${key.toString}"))
      }
    } yield Some(finalResult))
      .recover {
        case NoResultException => None
      }
//    db.run(lookupKeyQuery(key).result).map {
//      case Seq(result) => Some(fromDB(result))
//      case Seq() => None
//      case _ =>
//        throw SchemaConstraintViolation(s"Multiple results returned for uri ${key.toString}")
//    }
  }

  def insert(hook: Y): Future[Int] = {
    val storeHook = toDB(hook)
    for {
      n: Int <- db.run(lookupHookQuery(hook).size.result)
      storedHook <- storeHook
      result <-
        if (n > 0) {
          Future.failed(DuplicateHookException(hook))
        } else {
          db.run(table += storedHook)
        }
    } yield result
  }

  def update(hook: Y): Future[Int] = {
    val storeHook = toDB(hook)
    for {
      storedHook <- storeHook
      result <-
        db.run(table.insertOrUpdate(storedHook))
    } yield result
  }

  protected def runKeyQuery(query: Query[Rep[String], String, Seq]): Future[Seq[X]] = {
    toKeys(db.run(query.result))
  }

}
