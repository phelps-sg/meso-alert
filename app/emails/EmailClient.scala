package emails

import play.api.Configuration

trait EmailClient {
  protected val config: Configuration
  protected val emailSmtpHost: String = config.get[String]("email.smtpHost")
  protected val emailSmtpPort: Int = config.get[Int]("email.smtpPort")
  protected val emailHost: String = config.get[String]("email.host")
  protected val emailPassword: String = config.get[String]("email.hostPassword")
  protected val emailDestination: String = config.get[String]("email.destination")
}
