package services
import com.google.inject.ImplementedBy
import controllers.EmailExecutionContext
import courier.{Envelope, Mailer, Text}
import emails.EmailClient
import play.api.Configuration

import javax.inject.Inject
import javax.mail.internet.InternetAddress
import scala.concurrent.Future

@ImplementedBy(classOf[MailManagerImpl])
trait MailManager {
  def sendEmail(subject: String, content: String): Future[Unit]
}

class MailManagerImpl @Inject()(protected val config: Configuration, implicit val
  emailExecutionContext: EmailExecutionContext)
  extends MailManager with EmailClient {

  val mailer: Mailer = Mailer(emailSmtpHost,emailSmtpPort)
    .auth(true)
    .as(emailHost, emailPassword)
    .startTls(true)()

  def sendEmail(subject: String, content: String): Future[Unit] =  {
    mailer(Envelope.from(new InternetAddress(emailHost))
      .to(new InternetAddress(emailDestination))
      .subject(subject)
      .content(Text(content)))
  }
}
