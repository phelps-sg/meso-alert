import actors.{Registered, Started, Stopped}
import dao.Webhook

import scala.concurrent.Future

package object services {

  trait HooksManagerService[X, Y] {
    def init(): Future[Seq[Started[X]]]
    def start(key: Y): Future[Started[X]]
    def stop(key: Y): Future[Stopped[X]]
    def register(hook: Webhook): Future[Registered[X]]
  }

}
