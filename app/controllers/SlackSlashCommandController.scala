package controllers

import actions.SlackSignatureVerifyAction
import actors.{HookAlreadyStartedException, HookNotStartedException}
import akka.util.ByteString
import actions.SlackSignatureVerifyAction._
import com.slack.api.methods.request.auth.AuthTestRequest
import com.slack.api.methods.request.conversations.ConversationsMembersRequest
import dao._
import play.api.Logging
import play.api.i18n.{Lang, MessagesApi}
import play.api.mvc._
import services.{HooksManagerSlackChat, SlackManagerService}

import javax.inject.Inject
import scala.concurrent.{ExecutionContext, Future}
import scala.util.{Failure, Success, Try}

object SlackSlashCommandController {

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
  ): Try[SlashCommand] = {
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
            Success(
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
            Failure(new IllegalArgumentException("Malformed slash command"))
        }
      case _ =>
        Failure(
          new IllegalArgumentException(
            s"Malformed slash command- missing attributes ${attributes.filterNot(_.isEmpty)}"
          )
        )
    }
  }

}

class SlackSlashCommandController @Inject() (
    protected val slackSignatureVerifyAction: SlackSignatureVerifyAction,
    val controllerComponents: ControllerComponents,
    val slashCommandHistoryDao: SlashCommandHistoryDao,
    val slackTeamDao: SlackTeamDao,
    val hooksManager: HooksManagerSlackChat,
    messagesApi: MessagesApi,
    protected val slackManagerService: SlackManagerService
)(implicit val ec: ExecutionContext)
    extends BaseController
    with Logging {

  implicit val lang: Lang = Lang("en")

  def slashCommand: Action[ByteString] = {
    slackSignatureVerifyAction.async(parse.byteString) { request =>
      logger.debug("received slash command")
      request
        .validateSignatureAgainstBody()
        .map(processForm) match {
        case Success(result) => result
        case Failure(ex) =>
          ex.printStackTrace()
          Future { Unauthorized(ex.getMessage) }
      }
    }
  }

  def processForm(formBody: Map[String, Seq[String]]): Future[Result] = {
    SlackSlashCommandController.param("ssl_check")(formBody) match {

      case Some("1") =>
        Future { Ok }

      case _ =>
        SlackSlashCommandController.toCommand(formBody) match {

          case Success(slashCommand) =>
            val channelID = slashCommand.channelId.value
            val accessToken = for {
              team <- slackTeamDao.find(slashCommand.teamId)
            } yield team.accessToken

            accessToken flatMap { token =>
              isBotInvited(token, channelID) flatMap {
                case true =>
                  val recordCommand =
                    slashCommandHistoryDao.record(slashCommand)

                  for {
                    _ <- recordCommand
                    result <- process(slashCommand)
                  } yield result

                case false =>
                  logger.info(
                    "Bot is not invited to channel, cannot process command."
                  )
                  Future {
                    Ok(messagesApi("slackResponse.notInvitedError"))
                  }
              }
            }

          case Failure(ex) =>
            logger.error(ex.getMessage)
            Future {
              NotAcceptable(ex.getMessage)
            }
        }
    }
  }

  def isBotInvited(token: String, channelID: String): Future[Boolean] = {
    val authTestRequest =
      AuthTestRequest
        .builder()
        .token(token)
        .build()
    val selfAuthResponse = slackManagerService.authTest(authTestRequest)
    selfAuthResponse flatMap { response =>
      val botUserId = response.getUserId
      val conversationsMembersRequest = ConversationsMembersRequest
        .builder()
        .channel(channelID)
        .token(token)
        .build()
      val conversationsMembersResponse =
        slackManagerService.conversationsMembers(conversationsMembersRequest)
      conversationsMembersResponse map { response =>
        Option(response.getMembers) match {
          case None =>
            false
          case Some(members) =>
            members.contains(botUserId)
        }
      }
    }
  }

  def channel(implicit slashCommand: SlashCommand): SlackChannelId =
    slashCommand.channelId

  def cryptoAlert(implicit slashCommand: SlashCommand): Future[Result] = {

    val args = slashCommand.text.toLowerCase.split("\\s+")

    args match {

      case Array(_) | Array(_, "btc") =>
        args.head.toLongOption match {

          case Some(amount) =>
            logger.debug(s"amount = $amount")

            val f = for {
              team <- slackTeamDao.find(slashCommand.teamId)
              _ <- hooksManager.update(
                SlackChatHook(
                  channel,
                  token = team.accessToken,
                  amount * 100000000,
                  isRunning = true
                )
              )
              started <- hooksManager.start(channel)
            } yield started
            f.map { _ =>
              Ok(messagesApi("slackResponse.cryptoAlertNew", amount))
            }.recoverWith { case HookAlreadyStartedException(_) =>
              val f = for {
                _ <- hooksManager.stop(channel)
                restarted <- hooksManager.start(channel)
              } yield restarted
              f.map { _ =>
                Ok(messagesApi("slackResponse.cryptoAlertReconfig", amount))
              }
            }

          case None =>
            logger.debug(s"Invalid amount ${slashCommand.text}")
            Future {
              Ok(messagesApi("slackResponse.generalError"))
            }

        }

      case Array(_, _) =>
        Future {
          Ok(messagesApi("slackResponse.currencyError"))
        }

      case _ =>
        Future {
          Ok(messagesApi("slackResponse.generalError"))
        }

    }

  }

  def pauseAlerts(implicit slashCommand: SlashCommand): Future[Result] = {
    logger.debug("Pausing alerts")
    val f = for {
      stopped <- hooksManager.stop(channel)
    } yield stopped
    f.map { _ => Ok(messagesApi("slackResponse.pauseAlerts")) }
      .recover { case HookNotStartedException(_) =>
        Ok(messagesApi("slackResponse.pauseAlertsError"))
      }
  }

  def resumeAlerts(implicit slashCommand: SlashCommand): Future[Result] = {
    logger.debug("Resuming alerts")
    val f = for {
      started <- hooksManager.start(channel)
    } yield started
    f.map { _ => Ok(messagesApi("slackResponse.resumeAlerts")) }
      .recover { case HookAlreadyStartedException(_) =>
        Ok(messagesApi("slackResponse.resumeAlertsError"))
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
