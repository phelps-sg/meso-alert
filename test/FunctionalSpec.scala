import actors.TxWatchActor._
import akka.Done
import akka.actor.ActorSystem
import akka.http.scaladsl.Http
import akka.http.scaladsl.model._
import akka.http.scaladsl.model.headers.Origin
import akka.http.scaladsl.model.ws._
import akka.stream.scaladsl._
import org.scalatest.concurrent.ScalaFutures
import org.scalatestplus.play._
import play.api.inject.guice.GuiceApplicationBuilder
import play.api.test.{Helpers, TestServer}

import scala.concurrent.duration._
import scala.concurrent.{Await, Future}
import scala.util.{Failure, Success}

class FunctionalSpec extends PlaySpec with ScalaFutures {

  "HomeController" should {

    "reject a websocket flow if the origin is set incorrectly" in {

      implicit val system: ActorSystem = ActorSystem()
      import system.dispatcher
      lazy val port: Int = 9000
      val app = new GuiceApplicationBuilder().build()
      Helpers.running(TestServer(port, app)) {
        val myPublicAddress = s"localhost:$port"
        val serverURL = s"ws://$myPublicAddress/ws"

        val incoming = {
          Sink.foreach[Message] {
            case message: TextMessage.Strict =>
              println(message.text)
            case _ =>
            // ignore other message types
          }
        }

        // send this as a message over the WebSocket
        //        val outgoing = Source.single(TextMessage("hello world!"))
        val outgoing = Source.single(Auth("guest", "test").message)
        //        val outgoing = Source.empty

        val originPort = 10000
        val webSocketFlow =
          Http().webSocketClientFlow(WebSocketRequest(serverURL, extraHeaders = Seq(Origin(s"ws://localhost:$originPort"))))

        val (upgradeResponse, closed) =
          outgoing
            .viaMat(webSocketFlow)(Keep.right) // keep the materialized Future[WebSocketUpgradeResponse]
            .toMat(incoming)(Keep.both) // also keep the Future[Done]
            .run()

        val connected = upgradeResponse.flatMap { upgrade =>
          if (upgrade.response.status == StatusCodes.SwitchingProtocols)
            fail("successfully upgraded connection without correct origin")
          else
            Future.successful(upgrade.response.status)
        }

        connected.onComplete {
          case Success(_) => println("connected")
          case Failure(ex) =>
            ex.printStackTrace()
        }
        closed.foreach(_ => println("closed"))

        Await.result(connected, atMost = 1.minute)
      }
    }

    "accept a websocket flow if the origin is set correctly" in {

      implicit val system: ActorSystem = ActorSystem()
      import system.dispatcher
      lazy val port: Int = 9000
      val app = new GuiceApplicationBuilder().build()
      Helpers.running(TestServer(port, app)) {
        val myPublicAddress = s"localhost:$port"
        val serverURL = s"ws://$myPublicAddress/ws"

        val incoming = {
          Sink.foreach[Message] {
            case message: TextMessage.Strict =>
              println(message.text)
            case _ =>
            // ignore other message types
          }
        }

        val outgoing = Source.single(Auth("guest", "test").message)

        val webSocketFlow =
          Http().webSocketClientFlow(WebSocketRequest(serverURL, extraHeaders = Seq(Origin(s"ws://localhost:$port"))))

        val (upgradeResponse, closed) =
          outgoing
            .viaMat(webSocketFlow)(Keep.right) // keep the materialized Future[WebSocketUpgradeResponse]
            .toMat(incoming)(Keep.both) // also keep the Future[Done]
            .run()

        val connected = upgradeResponse.flatMap { upgrade =>
          if (upgrade.response.status == StatusCodes.SwitchingProtocols) Future.successful(Done) else throw new RuntimeException(s"Connection failed: ${upgrade.response.status}")
        }

        connected.onComplete {
          case Success(_) => println("connected")
          case Failure(ex) =>
            ex.printStackTrace()
        }

        Await.result(connected, atMost = 1.minute)
      }
    }

  }

}
