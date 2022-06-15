package controllers

import actors.{HookAlreadyStartedException, HookNotStartedException}
import akka.actor.ActorSystem
import dao.{SlackChannel, SlackChatHook, SlashCommand}
import org.slf4j.LoggerFactory
import play.api.mvc.{Action, BaseController, ControllerComponents}
import services.HooksManagerSlackChat

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

class SlackController @Inject()(val controllerComponents: ControllerComponents,
                                val hooksManager: HooksManagerSlackChat)
                               (implicit system: ActorSystem, ex: ExecutionContext) extends BaseController {

  private val logger = LoggerFactory.getLogger(classOf[SlackController])

  private val coreAttributes = List("channel_id", "command", "text")
  private val optionalAttributes = List("team_domain", "team_id", "channel_name",
                                  "user_id", "user_name", "is_enterprise_install")

  def slashCommand: Action[Map[String, Seq[String]]] = Action.async(parse.formUrlEncoded) { request =>

    logger.debug("received slash command")
    request.body.foreach { x => logger.debug(s"${x._1} = ${x._2}") }

    val paramMap = request.body

    def param(key: String): Option[String] = paramMap(key).headOption

    def toCommand: Try[SlashCommand] = {
      val attributes = coreAttributes.map(param)
      attributes match {
        case List(Some(channelId), Some(command), Some(args)) =>
          optionalAttributes.map(param) match {
            case List(teamDomain, teamId, channelName, userId, userName, isEnterpriseInstall) =>
              Success(SlashCommand(channelId, command, args, teamDomain, teamId, channelName, userId, userName, isEnterpriseInstall))
            case _ =>
              Failure(new IllegalArgumentException("Malformed slash command"))
          }
        case _ =>
          Failure(new IllegalArgumentException(s"Malformed slash command- missing attributes ${attributes.filterNot(_.isEmpty)}"))
      }
    }

    toCommand match {

      case Success(command) =>

        val channel = SlackChannel(command.channelId)

        command match {
          case "/crypto-alert" =>
            command.text.toLongOption match {

              case Some(amount) =>
                logger.debug(s"amount = $amount")
                val f = for {
                  _ <- hooksManager.update(SlackChatHook(channel, amount * 100000000))
                  started <- hooksManager.start(channel)
                } yield started
                f.map { _ => Ok(s"OK, I will send updates on any BTC transactions exceeding $amount BTC.") }
                  .recoverWith {
                    case HookAlreadyStartedException(_) =>
                      val f = for {
                        _ <- hooksManager.stop(channel)
                        restarted <- hooksManager.start(channel)
                      } yield restarted
                      f.map { _ => Ok(s"OK, I have reconfigured the alerts on this channel with a new threshold of $amount BTC.") }
                  }

              case None =>
                logger.debug(s"Invalid amount ${command.text}")
                Future {
                  Ok(s"Usage: `/crypto-alert [threshold amount in BTC]`")
                }

            }

          case "/pause-alerts" =>
            logger.debug("Pausing alerts")
            val f = for {
              stopped <- hooksManager.stop(channel)
            } yield stopped
            f.map { _ => Ok("OK, I have paused alerts for this channel.") }
              .recover {
                case HookNotStartedException(_) =>
                  Ok("Alerts are not active on this channel.")
              }

          case "/resume-alerts" =>
            logger.debug("Resuming alerts")
            val f = for {
              stopped <- hooksManager.start(channel)
            } yield stopped
            f.map { _ => Ok("OK, I will resume alerts on this channel.") }
              .recover {
                case HookAlreadyStartedException(_) =>
                  Ok("Alerts are already active on this channel.")
              }

        }

      case Failure(ex) =>
        logger.error(ex.getMessage)
        Future { ServiceUnavailable(ex.getMessage) }
    }

  }

}
