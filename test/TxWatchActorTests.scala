import org.scalamock.scalatest.MockFactory
import org.scalatestplus.play.PlaySpec
import play.api.inject.guice.GuiceApplicationBuilder

//noinspection TypeAnnotation
class TxWatchActorTests extends PlaySpec with MockFactory {

  def fixture = new {
    val app = new GuiceApplicationBuilder().build()
  }

}
