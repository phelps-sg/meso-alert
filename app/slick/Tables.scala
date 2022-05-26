package slick

import dao.Webhook

import java.net.URI

object Tables {

  val profile: BtcPostgresProfile.type = BtcPostgresProfile
  import profile.api._

  def ws(hook: Webhook): Option[(String, Long)] = {
    Some((hook.uri.toURL.toString, hook.threshold))
  }

  def toWebhook(tuple: (String, Long)): Webhook = Webhook(new URI(tuple._1), tuple._2)

  class Webhooks(tag: Tag) extends Table[Webhook](tag, "webhooks") {
    def url = column[String]("url", O.PrimaryKey)
    def threshold = column[Long]("threshold")
    def * = (url, threshold) <> (
      h => Webhook(new URI(h._1), h._2),
      (h: Webhook) => {
        Some(h.uri.toString, h.threshold)
      }
    )
  }

  val webhooks = TableQuery[Webhooks]

  val schema = webhooks.schema
}
