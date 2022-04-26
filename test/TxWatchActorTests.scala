import actors.TxWatchActor
import actors.TxWatchActor.{Auth, TxUpdate}
import akka.actor.{Actor, ActorSystem, Props}
import akka.pattern.ask
import akka.testkit.{ImplicitSender, TestKit}
import com.github.nscala_time.time.Imports.DateTime
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import play.api.test.Helpers.defaultAwaitTimeout
import services.{MemPoolWatcherService, User, UserManagerService}

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.Await
import scala.concurrent.duration.DurationInt

//noinspection TypeAnnotation
class TxWatchActorTests extends TestKit(ActorSystem("MySpec"))
  with Matchers
  with AnyWordSpecLike
  with MockFactory
  with ImplicitSender {

  object MockWebsocketActor {
    def props(updates: ArrayBuffer[TxUpdate]) = Props(new MockWebsocketActor(updates))
  }

  class MockWebsocketActor(val updates: ArrayBuffer[TxUpdate]) extends Actor {

    override def receive: Receive = {
      case update: TxUpdate =>
        updates += update
      case _ =>
        fail("unrecognized message format")
    }
  }

  def fixture = new {
    val mockMemPoolWatcher = mock[MemPoolWatcherService]
    (mockMemPoolWatcher.addListener _).expects(*)
    val updates = ArrayBuffer[TxUpdate]()
    val wsActor = system.actorOf(MockWebsocketActor.props(updates))
    val mockUser = mock[User]
    val mockUserManager = mock[UserManagerService]
    val txWatchActor = system.actorOf(TxWatchActor.props(wsActor, mockMemPoolWatcher, mockUserManager))
  }

  "TxWatchActor" should {

    "provide updates when a valid user sends authentication" in {
      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true)
      val f = fixture
      (f.mockUser.filter _).expects(tx).returning(true)
      (f.mockUserManager.authenticate _).expects("test").returning(f.mockUser)
      f.txWatchActor ! Auth("test", "test")
      f.txWatchActor ! tx
      f.updates.head mustBe tx
    }
  }

}
