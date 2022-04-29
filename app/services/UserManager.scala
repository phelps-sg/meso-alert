package services

import actors.TxUpdate

import javax.inject.Singleton
import com.google.inject.ImplementedBy

case class InvalidCredentialsException() extends Exception

abstract case class User(id: String) {
  def filter(tx: TxUpdate): Boolean
}

@ImplementedBy(classOf[UserManager])
trait UserManagerService {
  def authenticate(id: String): User
}

@Singleton
class UserManager extends UserManagerService {

  val guest: User = new User("guest") {
    def filter(tx: TxUpdate): Boolean = {
      tx.value > 1000000000
    }
  }

  def authenticate(id: String): User = {
    if (id == "guest") {
      guest
    } else {
      throw InvalidCredentialsException()
    }
  }

}
