import actors.TxUpdate
import com.google.inject.ImplementedBy

import java.net.URI
import scala.concurrent.Future

package object dao {

  trait Filter {
    def filter(tx: TxUpdate): Boolean
  }

  trait ThresholdFilter extends Filter {
    val threshold: Long
    def filter(tx: TxUpdate): Boolean = tx.value >= threshold
  }

  trait HookDao[X, Y] {
    def init(): Future[Unit]
    def all(): Future[Seq[Y]]
    def allKeys(): Future[Seq[X]]
    def find(uri: X): Future[Option[Y]]
    def insert(hook: Y): Future[Int]
  }

  case class SlackChannel(id: String)
  case class Webhook(uri: URI, threshold: Long) extends ThresholdFilter
  case class SlackChatHook(channel: SlackChannel, threshold: Long) extends ThresholdFilter
  case class DuplicateHookException[X](uri: X) extends Exception(s"A hook already exists with key $uri")

  @ImplementedBy(classOf[SlickWebhookDao])
  trait WebhookDao extends HookDao[URI, Webhook]

  @ImplementedBy(classOf[SlickSlackChatDao])
  trait SlackChatHookDao extends HookDao[SlackChannel, SlackChatHook]

}
