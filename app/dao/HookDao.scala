package dao

import com.google.inject.ImplementedBy

import java.net.URI
import scala.annotation.unused
import scala.concurrent.Future

trait HookDao[X, Y <: Hook[X]] {
  protected def initialiseFuture(): Future[Unit]
//  def all(): Future[Seq[Hook[X]]]
  def allKeys(): Future[Seq[_ <: X]]
  def allRunningKeys(): Future[Seq[_ <: X]]
  def find(@unused key: X): Future[_ <: Hook[X]]
  def insert(@unused hook: Y): Future[Int]
  def update(@unused hook: Y): Future[Int]
}

@ImplementedBy(classOf[SlickWebhookDao])
trait WebhookDao extends HookDao[URI, Webhook]

@ImplementedBy(classOf[SlickSlackChatDao])
trait SlackChatHookDao extends HookDao[SlackChannel, SlackChatHook]
