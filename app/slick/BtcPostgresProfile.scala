package slick

import com.github.tminglei.slickpg.ExPostgresProfile
import slick.basic.Capability
import slick.basic.Capability

import scala.collection.mutable

trait BtcPostgresProfile extends ExPostgresProfile { // with PgArraySupport {

  override protected def computeCapabilities: Set[Capability] =
    super.computeCapabilities + slick.jdbc.JdbcCapabilities.insertOrUpdate

  //noinspection TypeAnnotation
//  override val api = MyAPI

  //noinspection TypeAnnotation
//  object MyAPI extends API with ArrayImplicits {
//    implicit val simpleLongBufferTypeMapper =
//      new SimpleArrayJdbcType[Long]("_int8").to(_.toBuffer[Long], (v: mutable.Buffer[Long]) => v.toSeq)
//  }
  //    with JsonImplicits {
  //    implicit val strListTypeMapper = new SimpleArrayJdbcType[String]("text").to(_.toList)
  //    implicit val playJsonArrayTypeMapper =
  //      new AdvancedArrayJdbcType[JsValue](pgjson,
  //        (s) => utils.SimpleArrayUtils.fromString[JsValue](Json.parse(_))(s).orNull,
  //        (v) => utils.SimpleArrayUtils.mkString[JsValue](_.toString())(v)
  //      ).to(_.toList)
  //  }
}

object BtcPostgresProfile extends BtcPostgresProfile
