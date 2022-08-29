package util

import org.abstractj.kalium.NaCl
import org.abstractj.kalium.crypto.Random
import util.Encodings.base64Encode

object GenerateSodiumKey {

  def main(args: Array[String]): Unit = {
    val result = NaCl.init()
    if (result < 0) {
      throw new RuntimeException(
        s"Could not initialise sodium library: $result"
      )
    }
    val rng = new Random()
    val encoder = java.util.Base64.getEncoder
    val key = base64Encode(rng.randomBytes(32))
    println(s"private-key: $key")
  }

}
