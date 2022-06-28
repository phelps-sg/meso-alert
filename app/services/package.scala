import akka.util.Timeout

import scala.concurrent.duration.DurationInt

package object services {

  implicit val timeout: Timeout = 1.minute

}
