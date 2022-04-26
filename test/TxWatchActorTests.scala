import actors.TxWatchActor
import actors.TxWatchActor.{Auth, TxUpdate}
import akka.actor.{Actor, ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit, TestProbe}
import com.github.nscala_time.time.Imports.DateTime
import org.scalamock.scalatest.MockFactory
import org.scalatest.matchers.must.Matchers.convertToAnyMustWrapper
import org.scalatest.matchers.should.Matchers
import org.scalatest.wordspec.AnyWordSpecLike
import services.{InvalidCredentialsException, MemPoolWatcherService, User, UserManagerService}

import scala.collection.mutable

//noinspection TypeAnnotation
class TxWatchActorTests extends TestKit(ActorSystem("MySpec"))
  with Matchers
  with AnyWordSpecLike
  with MockFactory
  with ImplicitSender {

  object MockWebsocketActor {
    def props(updates: mutable.Stack[TxUpdate]) = Props(new MockWebsocketActor(updates))
  }

  class MockWebsocketActor(val updates: mutable.Stack[TxUpdate]) extends Actor {

    override def receive: Receive = {
      case update: TxUpdate =>
        updates += update
      case _ =>
        fail("unrecognized message format")
    }
  }

  def fixture = new {
    val mockMemPoolWatcher = mock[MemPoolWatcherService]
    val updates = mutable.Stack[TxUpdate]()
    val wsActor = system.actorOf(MockWebsocketActor.props(updates))
    val mockUser = mock[User]
    val mockUserManager = mock[UserManagerService]
    val txWatchActor = system.actorOf(TxWatchActor.props(wsActor, mockMemPoolWatcher, mockUserManager))
  }

  "TxWatchActor" should {

    "provide updates when user is authentication" in {

      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true)

      val f = fixture
      (f.mockUser.filter _).expects(tx).returning(true)
      (f.mockUserManager.authenticate _).expects("test").returning(f.mockUser)
      (f.mockMemPoolWatcher.addListener _).expects(*)

      f.txWatchActor ! Auth("test", "test")
      expectNoMessage()

      f.txWatchActor ! tx
      expectNoMessage()

      f.updates.head mustBe tx
    }

    "not provide updates when credentials are invalid" in {

      val tx = TxUpdate("testHash", 10, DateTime.now(), isPending = true)

      val f = fixture
      (f.mockUserManager.authenticate _).expects("test").throws(InvalidCredentialsException())

      val probe = TestProbe()
      probe.watch(f.txWatchActor)

      f.txWatchActor ! Auth("test", "test")
      expectNoMessage()

      probe.expectTerminated(f.txWatchActor)

      f.txWatchActor ! tx
      expectNoMessage()

      f.updates.isEmpty mustBe true
    }
  }

  "only provide updates according to the user's filter" in {

    val tx1 = TxUpdate("testHash1", 10, DateTime.now(), isPending = true)
    val tx2 = TxUpdate("testHash2", 1, DateTime.now(), isPending = true)

    val f = fixture
    (f.mockUserManager.authenticate _).expects("test").returning(f.mockUser)
    (f.mockMemPoolWatcher.addListener _).expects(*)


    f.txWatchActor ! Auth("test", "test")
    expectNoMessage()

    (f.mockUser.filter _).expects(tx1).returning(true)
    f.txWatchActor ! tx1
    f.updates.pop() mustBe tx1

    (f.mockUser.filter _).expects(tx2).returning(false)
    f.txWatchActor ! tx2
    f.updates.isEmpty mustBe true
  }

}
