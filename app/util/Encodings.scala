package util

object Encodings {

  val encoder = java.util.Base64.getEncoder

  def base64Encode(data: Array[Byte]): String =
    encoder.encode(data).map(_.toChar).mkString
}
