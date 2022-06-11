import com.google.inject.ImplementedBy

import java.net.URI
import scala.concurrent.Future

package object dao {

  trait HookWithThreshold {
    val threshold: Long
  }

  trait HookDao[X, Y] {
    def init(): Future[Unit]
    def all(): Future[Seq[Y]]
    def allKeys(): Future[Seq[X]]
    def find(uri: X): Future[Option[Y]]
    def insert(hook: Y): Future[Int]
  }

  case class SlackChannel(id: String)
  case class Webhook(uri: URI, threshold: Long) extends HookWithThreshold
  case class SlackChatHook(channel: SlackChannel, threshold: Long) extends HookWithThreshold
  case class DuplicateHookException[X](uri: X) extends Exception(s"A hook already exists with key $uri")

  @ImplementedBy(classOf[SlickWebhookDao])
  trait WebhookDao extends HookDao[URI, Webhook]

  @ImplementedBy(classOf[SlickSlackChatDao])
  trait SlackChatHookDao extends HookDao[SlackChannel, SlackChatHook]

}
