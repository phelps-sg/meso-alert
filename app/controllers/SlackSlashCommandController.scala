package controllers

import actors.{
  HookAlreadyStartedException,
  HookNotRegisteredException,
  HookNotStartedException
}
import akka.util.ByteString
import com.mesonomics.playhmacsignatures.SignatureVerifyAction.formUrlEncodedParser
import com.mesonomics.playhmacsignatures.{
  HMACSignatureHelpers,
  SlackSignatureVerifyAction
}
import controllers.SlackSlashCommandController._
import dao._
import play.api.Logging
import play.api.i18n.{Lang, MessagesApi}
import play.api.mvc._
import services.{HooksManagerSlackChat, SlackManagerService}
import slack.BoltException

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

object SlackSlashCommandController {

  val MESSAGE_CRYPTO_ALERT_HELP: String = "slackResponse.cryptoAlertHelp"
  val MESSAGE_CRYPTO_ALERT_NEW: String = "slackResponse.cryptoAlertNew"
  val MESSAGE_CRYPTO_ALERT_RECONFIG: String =
    "slackResponse.cryptoAlertReconfig"
  val MESSAGE_CRYPTO_ALERT_BOT_NOT_IN_CHANNEL: String =
    "slackResponse.cryptoAlertBotInChannel"
  val MESSAGE_CRYPTO_ALERT_MIN_AMOUNT_ERROR: String =
    "slackResponse.cryptoAlertMinAmountError"
  val MESSAGE_GENERAL_ERROR: String = "slackResponse.generalError"
  val MESSAGE_CURRENCY_ERROR: String = "slackResponse.currencyError"
  val MESSAGE_PAUSE_ALERTS_HELP: String = "slackResponse.pauseAlertsHelp"
  val MESSAGE_PAUSE_ALERTS: String = "slackResponse.pauseAlerts"
  val MESSAGE_PAUSE_ALERTS_ERROR: String = "slackResponse.pauseAlertsError"
  val MESSAGE_RESUME_ALERTS_HELP: String = "slackResponse.resumeAlertsHelp"
  val MESSAGE_RESUME_ALERTS: String = "slackResponse.resumeAlerts"
  val MESSAGE_RESUME_ALERTS_ERROR: String = "slackResponse.resumeAlertsError"
  val MESSAGE_RESUME_ALERTS_ERROR_NOT_CONFIGURED =
    "slackResponse.resumeAlertsErrorNotConfigured"

  private val coreAttributes = List("channel_id", "command", "text")

  private val optionalAttributes = List(
    "team_domain",
    "team_id",
    "channel_name",
    "user_id",
    "user_name",
    "is_enterprise_install"
  )

  def param(key: String)(implicit
      paramMap: Map[String, Seq[String]]
  ): Option[String] =
    Try(paramMap(key).headOption).orElse(Success(None)).get

  def toCommand(implicit
      paramMap: Map[String, Seq[String]]
  ): Future[SlashCommand] = {
    val attributes = coreAttributes.map(param)
    attributes match {
      case Seq(Some(channelId), Some(command), Some(args)) =>
        optionalAttributes.map(param) match {
          case Seq(
                teamDomain,
                Some(teamId),
                channelName,
                userId,
                userName,
                isEnterpriseInstall
              ) =>
            Future.successful(
              SlashCommand(
                None,
                SlackChannelId(channelId),
                command,
                args,
                teamDomain,
                SlackTeamId(teamId),
                channelName,
                userId.map(SlackUserId),
                userName,
                isEnterpriseInstall.map(x =>
                  Try(x.toBoolean).orElse(Success(false)).get
                ),
                Some(java.time.LocalDateTime.now())
              )
            )
          case _ =>
            Future.failed(
              new IllegalArgumentException("Malformed slash command")
            )
        }
      case _ =>
        Future.failed(
          new IllegalArgumentException(
            s"Malformed slash command- missing attributes ${attributes.filterNot(_.isEmpty)}"
          )
        )
    }
  }

}

class SlackSlashCommandController @Inject() (
    val controllerComponents: ControllerComponents,
    val slashCommandHistoryDao: SlashCommandHistoryDao,
    val slackTeamDao: SlackTeamDao,
    val hooksManager: HooksManagerSlackChat,
    messagesApi: MessagesApi,
    protected val slackManagerService: SlackManagerService,
    protected implicit val slackSignatureVerifyAction: SlackSignatureVerifyAction
)(implicit val ec: ExecutionContext)
    extends BaseController
    with HMACSignatureHelpers
    with Logging {

  implicit val lang: Lang = Lang("en")

  private val onValidSignature =
    validateSignatureAsync(formUrlEncodedParser)(_)

  def slashCommand: Action[ByteString] = onValidSignature { body =>
    onSlashCommand(body) { slashCommand =>
      val f = for {
        _ <- slashCommandHistoryDao.record(slashCommand)
        result <- process(slashCommand)
      } yield result
      f recover { case ex: Exception =>
        logger.error(ex.getMessage)
        NotAcceptable(ex.getMessage)
      }
    }
  }

  def process(implicit slashCommand: SlashCommand): Future[Result] = {
    slashCommand.command match {
      case "/crypto-alert"  => cryptoAlert
      case "/pause-alerts"  => pauseAlerts
      case "/resume-alerts" => resumeAlerts
    }
  }

  private def cryptoAlert(implicit
      slashCommand: SlashCommand
  ): Future[Result] = {

    val args = slashCommand.text.toLowerCase.split("\\s+")

    args match {

      case Array("help") =>
        logger.debug("crypto-alert help")
        futureMessage(MESSAGE_CRYPTO_ALERT_HELP)

      case Array(_) | Array(_, "btc") =>
        args.head.toLongOption match {

          case Some(amount) if amount >= 1 =>
            logger.debug(s"amount = $amount")

            val f = for {
              team <- slackTeamDao.find(slashCommand.teamId)
              _ <- checkChannelPermissions(team)
              _ <- hooksManager.update(
                SlackChatHookPlainText(
                  channel,
                  token = team.accessToken,
                  Satoshi(amount * 100000000),
                  isRunning = true
                )
              )
              _ <- hooksManager.start(channel)
            } yield message(MESSAGE_CRYPTO_ALERT_NEW, amount)

            f.recoverWith {

              case HookAlreadyStartedException(_) =>
                for {
                  _ <- hooksManager.stop(channel)
                  _ <- hooksManager.start(channel)
                } yield message(MESSAGE_CRYPTO_ALERT_RECONFIG, amount)

              case BoltException.ChannelNotFoundException =>
                futureMessage(MESSAGE_CRYPTO_ALERT_BOT_NOT_IN_CHANNEL)
            }

          case Some(amount) if amount < 1 =>
            futureMessage(MESSAGE_CRYPTO_ALERT_MIN_AMOUNT_ERROR)

          case None =>
            logger.debug(s"Invalid amount ${slashCommand.text}")
            futureMessage(MESSAGE_GENERAL_ERROR)
        }

      case Array(_, _) =>
        futureMessage(MESSAGE_CURRENCY_ERROR)

      case _ =>
        futureMessage(MESSAGE_GENERAL_ERROR)
    }
  }

  private def pauseAlerts(implicit
      slashCommand: SlashCommand
  ): Future[Result] = {

    val args = slashCommand.text.toLowerCase.split("\\s+")

    args match {

      case Array("") =>
        logger.debug("Pausing alerts")
        val f = for {
          _ <- hooksManager.stop(channel)
        } yield message(MESSAGE_PAUSE_ALERTS)
        f.recover { case HookNotStartedException(_) =>
          message(MESSAGE_PAUSE_ALERTS_ERROR)
        }

      case _ =>
        futureMessage(MESSAGE_PAUSE_ALERTS_HELP)

    }
  }

  private def resumeAlerts(implicit
      slashCommand: SlashCommand
  ): Future[Result] = {
    val args = slashCommand.text.toLowerCase.split("\\s+")

    args match {

      case Array("") =>
        logger.debug("Resuming alerts")
        val f = for {
          team <- slackTeamDao.find(slashCommand.teamId)
          _ <- checkChannelPermissions(team)
          _ <- hooksManager.start(channel)
        } yield message(MESSAGE_RESUME_ALERTS)
        f recover {
          case HookAlreadyStartedException(_) =>
            message(MESSAGE_RESUME_ALERTS_ERROR)
          case HookNotRegisteredException(_) =>
            message(MESSAGE_RESUME_ALERTS_ERROR_NOT_CONFIGURED)
          case BoltException.ChannelNotFoundException =>
            message(MESSAGE_CRYPTO_ALERT_BOT_NOT_IN_CHANNEL)
        }

      case _ =>
        futureMessage(MESSAGE_RESUME_ALERTS_HELP)

    }
  }

  /** Check whether the body contains an SSL verification request (ssl_check=1)
    * and if so respond with 200, otherwise attempt to parse the body as a slash
    * command and process it using the supplied function.
    */
  private def onSlashCommand(
      body: Map[String, Seq[String]]
  )(processCommand: SlashCommand => Future[Result]): Future[Result] =
    SlackSlashCommandController.param("ssl_check")(body) match {
      case Some("1") =>
        Future.successful { Ok }
      case _ =>
        SlackSlashCommandController.toCommand(body).flatMap(processCommand)
    }

  /** Check whether we have permissions to execute the specified command.
    * @param team
    *   The team corresponding to the bot
    * @param slashCommand
    *   The command to be executed
    * @return
    *   Either return the unaltered team object, or throw an exception if
    *   insufficient permissions.
    */
  private def checkChannelPermissions(
      team: SlackTeam
  )(implicit slashCommand: SlashCommand): Future[SlackTeam] =
    // conversationInfo will throw BoltException("channel_not_found") for
    // private channels we are not a member of.
    for {
      _ <- slackManagerService.conversationsInfo(
        team.accessToken,
        channel
      )
    } yield team

  private def channel(implicit slashCommand: SlashCommand): SlackChannelId =
    slashCommand.channelId

  private def message(key: String, args: Any*): Result = Ok(
    messagesApi(key, args: _*)
  )

  private def futureMessage(key: String): Future[Result] =
    Future.successful(message(key))
}
