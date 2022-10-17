package services
import com.google.inject.ImplementedBy
import courier.{Envelope, Mailer, Text}
import play.api.Configuration
import slick.EmailExecutionContext

import javax.inject.{Inject, Singleton}
import javax.mail.internet.InternetAddress
import scala.concurrent.Future

@ImplementedBy(classOf[MailManagerService])
trait MailManager {
  def sendEmail(
      to: String,
      subject: String,
      content: String
  ): Future[Unit]
}

@Singleton
class MailManagerService @Inject() (
    protected val config: Configuration,
    implicit val emailExecutionContext: EmailExecutionContext
) extends MailManager {

  protected val host: String =
    config.get[String]("email.smtpHost")
  protected val port: Int = config.get[Int]("email.smtpPort")
  protected val password: String =
    config.get[String]("email.hostPassword")
  protected val username: String =
    config.get[String]("email.host")

  val mailer: Mailer =
    Mailer(host, port)
      .auth(true)
      .as(username, password)
      .startTls(true)()

  def sendEmail(
      to: String,
      subject: String,
      content: String
  ): Future[Unit] = {
    mailer(
      Envelope
        .from(new InternetAddress(username))
        .to(new InternetAddress(to))
        .subject(subject)
        .content(Text(content))
    )
  }
}
