package unittests

import actors.TxHash
import controllers.SlackSlashCommandController
import dao.SlashCommand
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import slack.BlockMessages.{
  MESSAGE_NEW_TRANSACTION,
  MESSAGE_TOO_MANY_OUTPUTS,
  MESSAGE_TO_ADDRESSES,
  MESSAGE_TRANSACTION_HASH,
  blockMessageBuilder
}
import unittests.Fixtures.{
  MessagesFixtures,
  SlackSignatureVerifierFixtures,
  SlickSlashCommandFixtures
}
import util.BitcoinFormatting.{formatSatoshi, linkToAddress, linkToTxHash}

import scala.util.{Failure, Success}

//noinspection TypeAnnotation
class FormattingTests extends AnyWordSpecLike with should.Matchers {

  "formatSatoshiValue" should {

    "return a value greater than 1 when value >= 100000000" in {
      formatSatoshi(100000000) shouldEqual "1"
      formatSatoshi(1000000000) shouldEqual "10"
    }

    "return a decimal value between 0 and 0.99999999 when 0 <= value < 100000000" in {
      formatSatoshi(0) shouldEqual "0.0"
      formatSatoshi(99999999) shouldEqual "0.99999999"
    }
  }

  "blockMessageBuilder" should {

    trait TestFixtures extends MessagesFixtures {
      val chatMessage: (TxHash, Long, Seq[String]) => String =
        blockMessageBuilder(messagesApi)
      val testHash = TxHash("testHash")
    }

    "print all outputs if they take up less than 47 sections" in new TestFixtures {
      chatMessage(
        testHash,
        10,
        List("1", "2")
      ) shouldEqual
        """[{"type":"header","text":{"type":"plain_text",""" +
        s""""text":"${messagesApi(MESSAGE_NEW_TRANSACTION)} ${formatSatoshi(
            10
          )}""" +
        """ BTC","emoji":false}},{"type":"section","text":{"type":"mrkdwn",""" +
        s""""text":"${messagesApi(
            MESSAGE_TRANSACTION_HASH
          )}: """ + linkToTxHash(testHash) +
        s""" ${messagesApi(MESSAGE_TO_ADDRESSES)}:"}},""" +
        """{"type":"section","text":{"type": "mrkdwn", "text": """" +
        s"${linkToAddress("1")}, ${linkToAddress("2")}, " +
        """"}}, {"type":"divider"}]"""
    }

    "make the last section of the block a link to view all the outputs if there are more than 47 sections" in
      new TestFixtures {
        val result: String = chatMessage(
          testHash,
          10,
          List.fill(1000)("testOutput")
        )
        result should include(
          """{"type":"section","text":{"type":"mrkdwn",""" +
            s""""text":"${messagesApi(
                MESSAGE_TOO_MANY_OUTPUTS
              )}"}},{"type":"divider"}]"""
        )
      }
  }

  "SlashCommandHistoryController" should {

    trait TestFixtures
        extends SlackSignatureVerifierFixtures
        with SlickSlashCommandFixtures

    "convert an incoming parameter map to a case class" in new TestFixtures {
      val paramMap =
        Map[String, Vector[String]](
          "channel_id" -> Vector(channelId.value),
          "team_id" -> Vector(slashCommandTeamId.value),
          "command" -> Vector(command),
          "text" -> Vector(text)
        )
      SlackSlashCommandController.toCommand(paramMap) should matchPattern {
        case Success(
              SlashCommand(
                None,
                `channelId`,
                `command`,
                `text`,
                None,
                `slashCommandTeamId`,
                None,
                None,
                None,
                None,
                Some(_: java.time.LocalDateTime)
              )
            ) =>
      }
    }

    "return an error when insufficient parameters are supplied" in new TestFixtures {
      val paramMap = {
        Map[String, Vector[String]](
          "channel_id" -> Vector(channelId.value)
        )
      }
      SlackSlashCommandController.toCommand(paramMap) should matchPattern {
        case Failure(_) =>
      }
    }
  }

}
