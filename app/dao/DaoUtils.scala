package dao

import scala.concurrent.{ExecutionContext, Future}

object DaoUtils {

  case object UpdateFailedException extends Exception("update failed")

  implicit class DBUpdateFuture(f: Future[Int]) {
    def withException(implicit ec: ExecutionContext): Future[Int] = f map {
      _ match {
        case 1 =>
          1
        case _ =>
          throw UpdateFailedException
      }
    }
  }

}
