package scala

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.awaitility.Awaitility.await
import org.awaitility.scala.AwaitilitySupport
import org.scalatest.{BeforeAndAfter, Suite}
import org.slf4j.{Logger, LoggerFactory}
import slick.BtcPostgresProfile.api._
import slick.{BtcPostgresProfile, Tables}

import scala.concurrent.duration.Duration
import scala.concurrent.{Await, ExecutionContext, ExecutionContextExecutor}

trait PostgresContainer extends ForAllTestContainer { // with BeforeAndAfter with AwaitilitySupport {
  self: Suite =>

  val logger: Logger = LoggerFactory.getLogger(classOf[PostgresContainer])

  override val container: PostgreSQLContainer = PostgreSQLContainer(
//    dockerImageNameOverride = "postgres:14.2",
    databaseName = "btc-test",
    username = "btc",
    password = "btc")

  implicit def database: BtcPostgresProfile.backend.DatabaseDef = {
    logger.info(s"Returning database definition from url ${container.jdbcUrl}")
    Database.forURL(url = container.jdbcUrl, user = container.username,
      password = container.password, driver = container.driverClassName)
  }

  implicit val timeout: Duration = Duration(1, "min")
  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

//  before {
//    Await.ready(database.run(DBIO.seq(Tables.schema.create)), timeout)
//  }
//
//  after {
//    Await.ready(database.run(DBIO.seq(Tables.schema.drop)), timeout)
//  }
}
