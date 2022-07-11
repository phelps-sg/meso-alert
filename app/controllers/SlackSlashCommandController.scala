package controllers

import actors.{HookAlreadyStartedException, HookNotStartedException}
import akka.actor.ActorSystem
import dao._
import play.api.Logging
import play.api.i18n.{DefaultMessagesApi, I18nSupport, Lang, Langs, Messages, MessagesApi, MessagesImpl}
import play.api.mvc.{Action, BaseController, ControllerComponents, Result}
import services.HooksManagerSlackChat

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object SlackSlashCommandController {

  private val coreAttributes = List("channel_id", "command", "text")

  private val optionalAttributes = List(
    "team_domain", "team_id", "channel_name", "user_id", "user_name", "is_enterprise_install"
  )

  def param(key: String)(implicit paramMap: Map[String, Seq[String]]): Option[String] =
    Try(paramMap(key).headOption).orElse(Success(None)).get

  def toCommand(implicit paramMap: Map[String, Seq[String]]): Try[SlashCommand] = {
    val attributes = coreAttributes.map(param)
    attributes match {
      case Seq(Some(channelId), Some(command), Some(args)) =>
        optionalAttributes.map(param) match {
          case Seq(teamDomain, Some(teamId), channelName, userId, userName, isEnterpriseInstall) =>
            Success(SlashCommand(None, channelId, command, args, teamDomain, teamId,
              channelName, userId, userName,
              isEnterpriseInstall.map(x => Try(x.toBoolean).orElse(Success(false)).get),
              Some(java.time.LocalDateTime.now())))
          case _ =>
            Failure(new IllegalArgumentException("Malformed slash command"))
        }
      case _ =>
        Failure(new IllegalArgumentException(s"Malformed slash command- missing attributes ${attributes.filterNot(_.isEmpty)}"))
    }
  }

}

class SlackSlashCommandController @Inject()(val controllerComponents: ControllerComponents,
                                            val slashCommandHistoryDao: SlashCommandHistoryDao,
                                            val slackTeamDao: SlackTeamDao,
                                            val hooksManager: HooksManagerSlackChat)
                                           (implicit system: ActorSystem, implicit val ec: ExecutionContext)
  extends BaseController with Logging with InitialisingController {


  override def init(): Future[Unit] = for {
    result <- slashCommandHistoryDao.init()
  } yield result

  def slashCommand: Action[Map[String, Seq[String]]] = Action.async(parse.formUrlEncoded) { request =>
    logger.debug("received slash command")
    request.body.foreach { x => logger.debug(s"${x._1} = ${x._2}") }

    SlackSlashCommandController.toCommand(request.body) match {

      case Success(slashCommand) =>
        val recordCommand = slashCommandHistoryDao.record(slashCommand)
        for {
          _ <- recordCommand
          result <- process(slashCommand)
        } yield result

      case Failure(ex) =>
        logger.error(ex.getMessage)
        Future { ServiceUnavailable(ex.getMessage) }
    }
  }

  def channel(implicit slashCommand: SlashCommand): SlackChannel = SlackChannel(slashCommand.channelId)

  def cryptoAlert(implicit slashCommand: SlashCommand): Future[Result] = {

    val args = slashCommand.text.toLowerCase.split("\\s+")

    args match {

      case Array(_) | Array(_, "btc") =>

        args.head.toLongOption match {

          case Some(amount) =>
            logger.debug(s"amount = $amount")

            val f = for {
              team <- slackTeamDao.find(slashCommand.teamId)
              _ <- {
                team match {
                  case Some(SlackTeam(_, _, _, accessToken, _)) =>
                    hooksManager.update(
                      SlackChatHook(channel, token = accessToken, amount * 100000000, isRunning = true)
                    )
                  case None =>
                    Future.failed(new IllegalArgumentException(s"No such slack team ${slashCommand.teamId}"))
                }
              }
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
            logger.debug(s"Invalid amount ${slashCommand.text}")
            Future {
              Ok("Usage: `/crypto-alert [threshold amount] [currency]`")
            }

        }

      case Array(_, _) =>
        val lang: Lang      = Lang("en")
        val message: String = messagesApi("slackResponse.currencyError")(lang)
        Future {
          Ok(message)
        }

      case _ =>
        Future {
          Ok("Usage: `/crypto-alert [threshold amount] [currency]`")
        }

    }

  }

  def pauseAlerts(implicit slashCommand: SlashCommand): Future[Result] = {
    logger.debug("Pausing alerts")
    val f = for {
      stopped <- hooksManager.stop(channel)
    } yield stopped
    f.map { _ => Ok("OK, I have paused alerts for this channel.") }
      .recover {
        case HookNotStartedException(_) =>
          Ok("Alerts are not active on this channel.")
      }
  }

  def resumeAlerts(implicit slashCommand: SlashCommand): Future[Result] = {
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

  def process(implicit slashCommand: SlashCommand): Future[Result] = {
    slashCommand.command match {
      case "/crypto-alert" => cryptoAlert
      case "/pause-alerts" => pauseAlerts
      case "/resume-alerts" => resumeAlerts
    }
  }

}
