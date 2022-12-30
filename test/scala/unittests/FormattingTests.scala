package unittests

import actors.RateLimitingBatchingActor.TxBatch
import actors.{TxHash, TxInputOutput, TxUpdate}
import controllers.SlackSlashCommandController
import dao._
import org.scalatest.Assertion
import org.scalatest.concurrent.ScalaFutures.convertScalaFuture
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import slack.BlockMessages
import slack.BlockMessages.{
  BlockMessage,
  MESSAGE_TOO_MANY_OUTPUTS,
  Section,
  txOutputsSections
}
import unittests.Fixtures.{
  ClockFixtures,
  MessagesFixtures,
  SlackChatHookFixtures,
  SlackSignatureVerifierFixtures,
  SlickSlashCommandFixtures,
  TxUpdateFixtures
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

    trait TestFixtures
        extends MessagesFixtures
        with ClockFixtures
        with TxUpdateFixtures {

      def addresses(labels: String*) =
        labels.map(label => TxInputOutput(Some(label), None))

      val markdownSectionRegEx =
        """\{"type":"section","text":\{"type":"mrkdwn","text":""".r

      val testContent = "test"
      val testHeader = BlockMessages.Header(testContent)
      val testSection = BlockMessages.Section(testContent)

      val txBatchToBlockMessage =
        BlockMessages.txBatchToBlockMessage(messagesApi)(_)
      val txToBlockMessage = BlockMessages.txToBlockMessage(messagesApi)(_)

      def chatMessageStr(
          txHash: TxHash,
          amount: Satoshi,
          addresses: Seq[TxInputOutput]
      ) = {
        val tx = TxUpdate(
          txHash,
          amount,
          time = now.toLocalDateTime,
          isPending = false,
          outputs = addresses,
          inputs = Vector(),
          confidence = None
        )
        txToBlockMessage(tx).render
      }

      def checkBraces(b: BlockMessages.Block): Assertion = {
        val rendered = b.render
        rendered.trim.take(1) shouldEqual "{"
        rendered.takeRight(1) shouldEqual "}"
      }

      def checkNumSections(numOutputs: Int): Assertion = {
        val m = chatMessageStr(
          testHash,
          Satoshi(100000000),
          addresses(List.range(1, numOutputs, 1).map(x => x.toString): _*)
        )
        println(m)
        val n = numOutputs / BlockMessages.MAX_TXS_PER_SECTION
        val r = numOutputs % BlockMessages.MAX_TXS_PER_SECTION
        val totalOutputsSections = if (r > 0) n + 1 else n
        markdownSectionRegEx
          .findAllIn(m)
          .length shouldBe 1 + totalOutputsSections
      }
    }

    "surround blocks with square brackets" in new TestFixtures {
      BlockMessage(List()).render shouldBe "[]"
    }

    "surround sections with braces" in new TestFixtures {
      checkBraces(testSection)
    }

    "surround headers with braces" in new TestFixtures {
      checkBraces(testHeader)
    }

    "surround dividers with braces" in new TestFixtures {
      checkBraces(BlockMessages.Divider)
    }

    "include the text in the header" in new TestFixtures {
      testHeader.render should include(testContent)
    }

    "include the text in the section" in new TestFixtures {
      testSection.render should include(testContent)
    }

    "print all outputs if they take up less than 47 sections - single section" in new TestFixtures {
      chatMessageStr(
        testHash,
        Satoshi(100000000),
        addresses("1", "2")
      ) should fullyMatch regex
        """\[\{"type":"header","text":\{"type":"plain_text","text":"New transaction with value [0-9]+ BTC","emoji":false\}\},\{"type":"section","text":\{"type":"mrkdwn","text":"Transaction Hash: <https://www\.blockchair\.com/bitcoin/transaction/[a-zA-Z0-9]+\|[a-zA-Z0-9]+> to addresses:"\}\},\{"type":"section","text":\{"type":"mrkdwn","text":"<https://www\.blockchair\.com/bitcoin/address/[a-zA-Z0-9]+\|[a-zA-Z0-9]+>, <https://www\.blockchair\.com/bitcoin/address/[a-zA-Z0-9]+\|[a-zA-Z0-9]+>"\}\},\{"type":"divider"\}]"""
    }

    "print all outputs if they take up less than 47 sections - multiple sections" in new TestFixtures {
      checkNumSections(30)
      checkNumSections(100)
    }

    "make the last section of the block a link to view all the outputs if there are more than 47 sections" in
      new TestFixtures {
        val result: String = chatMessageStr(
          testHash,
          Satoshi(100000000),
          addresses(List.fill(1000)("testOutput"): _*)
        )
        result should include(
          s""""text":"${messagesApi(MESSAGE_TOO_MANY_OUTPUTS)}"}"""
        )
      }

    "render a batch of transactions" in new TestFixtures {
      val txs = Vector(tx, tx1, tx2)
      val blocks = txBatchToBlockMessage(TxBatch(txs))
      txs.foreach { transaction =>
        blocks.render should include(transaction.hash.value)
      }
      blocks.components.last.render shouldNot include(
        messagesApi(BlockMessages.MESSAGE_TOO_MANY_TRANSACTIONS)
      )
    }

    "show appropriate message when including too many transactions in a batch" in new TestFixtures {
      val batch = TxBatch(Vector.fill(BlockMessages.MAX_BLOCKS)(tx))
      val result = txBatchToBlockMessage(batch)
      result.components.size shouldBe BlockMessages.MAX_BLOCKS + 1
      result.components.last.render should include(
        messagesApi(BlockMessages.MESSAGE_TOO_MANY_TRANSACTIONS)
      )
    }

    "render a batch containing a single tx identically to a single tx" in new TestFixtures {
      txToBlockMessage(tx) shouldEqual
        txBatchToBlockMessage(TxBatch(Vector(tx)))
    }

    "always ensure text fields are not empty even when no outputs are specified" in new TestFixtures {
      txOutputsSections(List(TxInputOutput(None, None))).foreach(block => {
        block match {
          case Section(text) =>
            text shouldNot equal("")
          case _ =>
            fail
        }
      })
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
          Satoshi(1),
          isRunning = false
        )
      val result = hook.toString()
      result should not include "secret"
    }
  }

}
