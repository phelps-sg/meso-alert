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

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Success, Try}

object SlackSlashCommandController {

  val MESSAGE_CRYPTO_ALERT_HELP: String = "slackResponse.cryptoAlertHelp"
  val MESSAGE_CRYPTO_ALERT_NEW: String = "slackResponse.cryptoAlertNew"
  val MESSAGE_CRYPTO_ALERT_RECONFIG: String =
    "slackResponse.cryptoAlertReconfig"
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

  private val onSignatureValid =
    validateSignatureAsync(formUrlEncodedParser)(_)

  def slashCommand: Action[ByteString] = onSignatureValid { body =>
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

  /** Check whether the body contains an SSL verification request (ssl_check=1)
    * and if so respond with 200, otherwise attempt to parse the body as a slash
    * command and process it using the supplied function.
    */
  def onSlashCommand(
      body: Map[String, Seq[String]]
  )(processCommand: SlashCommand => Future[Result]): Future[Result] =
    SlackSlashCommandController.param("ssl_check")(body) match {
      case Some("1") =>
        Future.successful { Ok }
      case _ =>
        SlackSlashCommandController.toCommand(body).flatMap(processCommand)
    }

  def channel(implicit slashCommand: SlashCommand): SlackChannelId =
    slashCommand.channelId

  def cryptoAlert(implicit slashCommand: SlashCommand): Future[Result] = {

    val args = slashCommand.text.toLowerCase.split("\\s+")

    args match {

      case Array("help") =>
        logger.debug("crypto-alert help")
        Future.successful {
          Ok(messagesApi(MESSAGE_CRYPTO_ALERT_HELP))
        }

      case Array(_) | Array(_, "btc") =>
        args.head.toLongOption match {

          case Some(amount) =>
            logger.debug(s"amount = $amount")

            val f = for {
              team <- slackTeamDao.find(slashCommand.teamId)
              _ <- hooksManager.update(
                SlackChatHookPlainText(
                  channel,
                  token = team.accessToken,
                  amount * 100000000,
                  isRunning = true
                )
              )
              started <- hooksManager.start(channel)
            } yield started
            f.map { _ =>
              Ok(messagesApi(MESSAGE_CRYPTO_ALERT_NEW, amount))
            }.recoverWith { case HookAlreadyStartedException(_) =>
              val f = for {
                _ <- hooksManager.stop(channel)
                restarted <- hooksManager.start(channel)
              } yield restarted
              f.map { _ =>
                Ok(messagesApi(MESSAGE_CRYPTO_ALERT_RECONFIG, amount))
              }
            }

          case None =>
            logger.debug(s"Invalid amount ${slashCommand.text}")
            Future.successful {
              Ok(messagesApi(MESSAGE_GENERAL_ERROR))
            }

        }

      case Array(_, _) =>
        Future.successful {
          Ok(messagesApi(MESSAGE_CURRENCY_ERROR))
        }

      case _ =>
        Future.successful {
          Ok(messagesApi(MESSAGE_GENERAL_ERROR))
        }

    }
  }

  def pauseAlerts(implicit slashCommand: SlashCommand): Future[Result] = {

    val args = slashCommand.text.toLowerCase.split("\\s+")

    args match {

      case Array("help") =>
        logger.debug("pause-alerts help")
        Future.successful {
          Ok(messagesApi(MESSAGE_PAUSE_ALERTS_HELP))
        }

      case Array("") =>
        logger.debug("Pausing alerts")
        val f = for {
          stopped <- hooksManager.stop(channel)
        } yield stopped
        f.map { _ => Ok(messagesApi(MESSAGE_PAUSE_ALERTS)) }
          .recover { case HookNotStartedException(_) =>
            Ok(messagesApi(MESSAGE_PAUSE_ALERTS_ERROR))
          }
    }
  }

  def resumeAlerts(implicit slashCommand: SlashCommand): Future[Result] = {
    val args = slashCommand.text.toLowerCase.split("\\s+")

    args match {

      case Array("help") =>
        logger.debug("resume-alerts help")
        Future.successful {
          Ok(messagesApi(MESSAGE_RESUME_ALERTS_HELP))
        }

      case Array("") =>
        logger.debug("Resuming alerts")
        val f = for {
          started <- hooksManager.start(channel)
        } yield started
        f.map { _ => Ok(messagesApi(MESSAGE_RESUME_ALERTS)) }
          .recover {
            case HookAlreadyStartedException(_) =>
              Ok(messagesApi(MESSAGE_RESUME_ALERTS_ERROR))
            case HookNotRegisteredException(_) =>
              Ok(messagesApi(MESSAGE_RESUME_ALERTS_ERROR_NOT_CONFIGURED))
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
}
