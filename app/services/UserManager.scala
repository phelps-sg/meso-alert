package services

import javax.inject.Singleton
import actors.TxWatchActor.TxUpdate
import com.google.inject.ImplementedBy

abstract case class User(id: String) {
  def filter(tx: TxUpdate): Boolean
}

@ImplementedBy(classOf[UserManager])
trait UserManagerService {
  def authenticate(id: String): User
}

@Singleton
class UserManager extends UserManagerService {

  case class NoSuchUserException(str: String) extends Exception

  val guest: User = new User("guest") {
    def filter(tx: TxUpdate): Boolean = {
      tx.value > 1000000000
    }
  }

  def authenticate(id: String): User = {
    if (id == "guest") {
      guest
    } else {
      throw NoSuchUserException(id)
    }
  }

}
