package services
import com.google.inject.ImplementedBy
import controllers.EmailExecutionContext
import courier.{Envelope, Mailer, Text}
import play.api.Configuration
import javax.inject.Singleton
import javax.inject.Inject
import javax.mail.internet.InternetAddress
import scala.concurrent.Future

@ImplementedBy(classOf[MailManagerService])
trait MailManager {
  def sendEmail(subject: String, content: String): Future[Unit]
}

@Singleton
class MailManagerService @Inject()(protected val config: Configuration,
                                   implicit val emailExecutionContext: EmailExecutionContext) extends MailManager {

  protected val emailSmtpHost: String = config.get[String]("email.smtpHost")
  protected val emailSmtpPort: Int = config.get[Int]("email.smtpPort")
  protected val emailHost: String = config.get[String]("email.host")
  protected val emailPassword: String = config.get[String]("email.hostPassword")
  protected val emailDestination: String = config.get[String]("email.destination")

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
