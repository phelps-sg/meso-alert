package util

import org.abstractj.kalium.crypto.Random

object GenerateSodiumKey {

  def main(args: Array[String]): Unit = {
    val rng = new Random()
    val encoder = java.util.Base64.getEncoder
    val key = encoder.encode(rng.randomBytes(32)).map(_.toChar).mkString
    println(s"private-key: $key")
  }

}
