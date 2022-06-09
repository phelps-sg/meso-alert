package services

import actors.WebhooksManagerActor
import dao.{SlackChannel, SlackChatHook}

import scala.concurrent.Future

trait SlackChatHookManagerService extends HooksManagerService[SlackChatHook, SlackChannel]

//class SlackChatManager extends SlackChatManagerService {
//  override def init(): Future[Seq[WebhooksManagerActor.Started[SlackChatHook]]] = ???
//
//  override def start(uri: SlackChannel): Future[WebhooksManagerActor.Started[SlackChatHook]] = ???
//
//  override def stop(uri: SlackChannel): Future[WebhooksManagerActor.Stopped[SlackChatHook]] = ???
//
//  override def register(hook: dao.Webhook): Future[WebhooksManagerActor.Registered[SlackChatHook]] = ???
//}
