import akka.actor.ActorSystem
import com.google.inject.{Inject, Singleton}
import play.libs.concurrent.CustomExecutionContext

package object slick {

  @Singleton
  class DatabaseExecutionContext @Inject()(system: ActorSystem)
    extends CustomExecutionContext(system, "database.dispatcher") {
  }

}
