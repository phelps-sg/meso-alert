package controllers

import actors.HookAlreadyRegisteredException
import akka.actor.ActorSystem
import dao.{SlackChannel, SlackChatHook}
import org.slf4j.LoggerFactory
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.HooksManagerSlackChat

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}

class SlackController @Inject()(val controllerComponents: ControllerComponents,
                                val hooksManager: HooksManagerSlackChat)
                               (implicit system: ActorSystem, ex: ExecutionContext) extends BaseController {

  private val logger = LoggerFactory.getLogger(classOf[SlackController])

  def slashCommand: Action[Map[String, Seq[String]]] = Action.async(parse.formUrlEncoded) { request =>

    logger.debug("received slash command")
    request.body.foreach { x => logger.debug(s"${x._1} = ${x._2}") }

    def param(key: String): String = request.body(key).head

    val channelId = param("channel_id")
    val command = param("command")
    val args = param("text")
    val channel = SlackChannel(channelId)

    command match {
      case "/crypto-alert" =>
        args.toLongOption match {

          case Some(amount) =>
            logger.debug(s"amount = $amount")
            val f = for {
              _ <- hooksManager.register(SlackChatHook(channel, amount * 100000000))
              started <- hooksManager.start(channel)
            } yield started
            f recover {
              case HookAlreadyRegisteredException(_) =>
                Ok("Alerts already registered for this channel")
            } map { _ => Ok("Success") }

          case None => Future { Ok(s"Invalid amount $args") }

        }
    }

  }

}
