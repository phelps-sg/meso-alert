package controllers

import actors.{HookAlreadyRegisteredException, HookAlreadyStartedException, HookNotStartedException}
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
              _ <- hooksManager.update(SlackChatHook(channel, amount * 100000000))
              started <- hooksManager.start(channel)
            } yield started
            f.map { _ => Ok(s"OK, I will send updates on any BTC transactions exceeding $amount BTC") }
              .recoverWith {
                case HookAlreadyStartedException(_) =>
                  val f = for {
                    _ <- hooksManager.stop(channel)
                    restarted <- hooksManager.start(channel)
                  } yield restarted
                  f.map { _ => Ok(s"OK I have reconfigured the alerts on this channel with new threshold of $amount BTC")}
              }

          case None =>
            logger.debug(s"Invalid amount $args")
            Future { Ok(s"Invalid amount $args") }
        }

      case "/pause-alerts" =>
        logger.debug("Pausing alerts")
        val f = for {
          stopped <- hooksManager.stop(channel)
        } yield stopped
        f.map { _ => Ok("Alerts paused for this channel") }
          .recover {
            case HookNotStartedException(_) =>
              Ok("Alerts not active on this channel")
          }

      case "/resume-alerts" =>
        logger.debug("Resuming alerts")
        val f = for {
          stopped <- hooksManager.start(channel)
        } yield stopped
        f.map { _ => Ok("Resuming alerts") }
        .recover {
          case HookAlreadyStartedException(_) =>
            Ok("Alerts already active on this channel")
        }

    }

  }

}
