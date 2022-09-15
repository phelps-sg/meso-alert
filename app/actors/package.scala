import actors.AuthenticationActor.TxInputOutput
import play.api.libs.json.{JsObject, Json, Writes}

import scala.collection.immutable.ArraySeq

//noinspection TypeAnnotation
package object actors {

  // scalafix:off
  implicit val txInputOutputWrites = new Writes[TxInputOutput] {
    def writes(inpOut: TxInputOutput): JsObject = {
      val addressField: Array[(String, Json.JsValueWrapper)] =
        Array(inpOut.address).filterNot(_.isEmpty).map("address" -> _.get)
      val valueField: Array[(String, Json.JsValueWrapper)] =
        Array(inpOut.value).filterNot(_.isEmpty).map("value" -> _.get)
      Json.obj(ArraySeq.unsafeWrapArray(addressField ++ valueField): _*)
    }
  }

  // scalafix:on
}
