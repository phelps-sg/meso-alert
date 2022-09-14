package unittests

import actors.formatSatoshi
import controllers.SlackSlashCommandController
import dao.SlashCommand
import org.scalatest.matchers.should
import org.scalatest.wordspec.AnyWordSpecLike
import unittests.Fixtures.{
  SlackSignatureVerifierFixtures,
  SlickSlashCommandFixtures
}

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
