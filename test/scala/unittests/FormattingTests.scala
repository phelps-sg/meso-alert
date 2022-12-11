package unittests

import actors.TxHash
import controllers.SlackSlashCommandController
import dao.{
  SlackAuthToken,
  SlackChannelId,
  SlackChatHookPlainText,
  SlashCommand,
  Satoshi
}
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import slack.BlockMessages.{
  Blocks,
  MESSAGE_TOO_MANY_OUTPUTS,
  blockMessageBuilder
}
import unittests.Fixtures.{
  MessagesFixtures,
  SlackChatHookFixtures,
  SlackSignatureVerifierFixtures,
  SlickSlashCommandFixtures
}
import util.BitcoinFormatting.formatSatoshi

//noinspection TypeAnnotation
class FormattingTests extends AnyWordSpecLike with should.Matchers {

  "formatSatoshiValue" should {

    "return a value greater than 1 when value >= 100000000" in {
      formatSatoshi(Satoshi(100000000)) shouldEqual "1"
      formatSatoshi(Satoshi(1000000000)) shouldEqual "10"
    }

    "return a decimal value between 0 and 0.99999999 when 0 <= value < 100000000" in {
      formatSatoshi(Satoshi(0)) shouldEqual "0.0"
      formatSatoshi(Satoshi(99999999)) shouldEqual "0.99999999"
    }
  }

  "blockMessageBuilder" should {

    trait TestFixtures extends MessagesFixtures {
      val chatMessage: (TxHash, Satoshi, Seq[String]) => Blocks =
        blockMessageBuilder(messagesApi)

      def chatMessageStr(
          txHash: TxHash,
          amount: Satoshi,
          addresses: Seq[String]
      ) =
        chatMessage(txHash, amount, addresses).value

      val testHash = TxHash("testHash")
    }

    "print all outputs if they take up less than 47 sections - single section" in new TestFixtures {
      chatMessageStr(
        testHash,
        Satoshi(100000000),
        List("1", "2")
      ) should fullyMatch regex
        """\[\{"type":"header","text":\{"type":"plain_text","text":"New transaction with value [0-9]+ BTC","emoji":false\}\},\{"type":"section","text":\{"type":"mrkdwn","text":"Transaction Hash: <https://www\.blockchair\.com/bitcoin/transaction/[a-zA-Z0-9]+\|[a-zA-Z0-9]+> to addresses:"\}\},\{"type":"section","text":\{"type": "mrkdwn", "text": "<https://www\.blockchair\.com/bitcoin/address/[a-zA-Z0-9]+\|[a-zA-Z0-9]+>, <https://www\.blockchair\.com/bitcoin/address/[a-zA-Z0-9]+\|[a-zA-Z0-9]+> "\}\}, \{"type":"divider"\}]"""
    }

    "print all outputs if they take up less than 47 sections - multiple sections" in new TestFixtures {
      chatMessageStr(
        testHash,
        Satoshi(100000000),
        List.range(1, 30, 1).map(x => x.toString)
      ) should fullyMatch regex
        """\[\{"type":"header","text":\{"type":"plain_text","text":"New transaction with value [0-9]+ BTC","emoji":false\}\},\{"type":"section","text":\{"type":"mrkdwn","text":"Transaction Hash: <https:\/\/www\.blockchair\.com\/bitcoin\/transaction\/[a-zA-Z0-9]+\|[a-zA-Z0-9]+> to addresses:"\}\},(\{"type":"section","text":\{"type": "mrkdwn", "text": "(<https:\/\/www\.blockchair\.com\/bitcoin\/address\/[a-zA-Z0-9]+\|[a-zA-Z0-9]+>(,)* )+"\}\}, )*\{"type":"divider"\}]"""

      chatMessageStr(
        testHash,
        Satoshi(100000000),
        List.range(1, 100, 1).map(x => x.toString)
      ) should fullyMatch regex
        """\[\{"type":"header","text":\{"type":"plain_text","text":"New transaction with value [0-9]+ BTC","emoji":false\}\},\{"type":"section","text":\{"type":"mrkdwn","text":"Transaction Hash: <https:\/\/www\.blockchair\.com\/bitcoin\/transaction\/[a-zA-Z0-9]+\|[a-zA-Z0-9]+> to addresses:"\}\},(\{"type":"section","text":\{"type": "mrkdwn", "text": "(<https:\/\/www\.blockchair\.com\/bitcoin\/address\/[a-zA-Z0-9]+\|[a-zA-Z0-9]+>(,)* )+"\}\}, )*\{"type":"divider"\}]"""

    }

    "make the last section of the block a link to view all the outputs if there are more than 47 sections" in
      new TestFixtures {
        val result: String = chatMessageStr(
          testHash,
          Satoshi(100000000),
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
        with SlackChatHookFixtures
        with SlickSlashCommandFixtures

    "convert an incoming parameter map to a case class" in new TestFixtures {
      val paramMap =
        Map[String, Vector[String]](
          "channel_id" -> Vector(channelId.value),
          "team_id" -> Vector(slashCommandTeamId.value),
          "command" -> Vector(command),
          "text" -> Vector(text)
        )
      SlackSlashCommandController
        .toCommand(paramMap)
        .futureValue should matchPattern {
        case SlashCommand(
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
            ) =>
      }
    }

    "return an error when insufficient parameters are supplied" in new TestFixtures {
      val paramMap = {
        Map[String, Vector[String]](
          "channel_id" -> Vector(channelId.value)
        )
      }
      val result = SlackSlashCommandController.toCommand(paramMap).failed
      result.futureValue should matchPattern { case _: Exception => }
    }
  }

  "SlackChatHookPlainText" should {
    "not reveal token when rendered as string" in {
      val hook =
        SlackChatHookPlainText(
          SlackChannelId("channel"),
          SlackAuthToken("secret"),
          1,
          isRunning = false
        )
      val result = hook.toString()
      result should not include "secret"
    }
  }

}
