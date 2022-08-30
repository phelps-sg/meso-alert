package util

object Encodings {

  val encoder = java.util.Base64.getEncoder
  val decoder = java.util.Base64.getDecoder

  def base64Encode(data: Array[Byte]): String =
    encoder.encode(data).map(_.toChar).mkString

  def base64Decode(string: String): Array[Byte] =
    decoder.decode(string)

}
