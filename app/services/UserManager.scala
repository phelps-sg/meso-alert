package services

import actors.TxUpdate
import com.google.inject.ImplementedBy

import javax.inject.Singleton
import scala.util.{Failure, Success, Try}

case object InvalidCredentialsException extends Exception

abstract case class User(id: String) {
  def filter(tx: TxUpdate): Boolean
}

@ImplementedBy(classOf[UserManager])
trait UserManagerService {
  def authenticate(id: String): Try[User]
}

@Singleton
class UserManager extends UserManagerService {

  val guest: User = new User("guest") {
    def filter(tx: TxUpdate): Boolean = {
      tx.value > 500000000L
    }
  }

  def authenticate(id: String): Try[User] = {
    if (id == "guest") {
      Success(guest)
    } else {
      Failure(InvalidCredentialsException)
    }
  }

}
