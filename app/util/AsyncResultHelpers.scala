package util

import play.api.mvc.Result

import scala.concurrent.Future

trait AsyncResultHelpers {

  implicit def resultToFuture(result: Result): Future[Result] =
    Future.successful(result)

}
