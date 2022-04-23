package services

import javax.inject.Singleton
import actors.TxWatchActor.TxUpdate

@Singleton
class UserManager {

  case class NoSuchUserException(str: String) extends Exception

  abstract case class User(id: String) {
    def filter(tx: TxUpdate): Boolean
  }

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
