package postgres

import com.dimafeng.testcontainers.{ForAllTestContainer, PostgreSQLContainer}
import org.scalatest.Suite
import org.slf4j.{Logger, LoggerFactory}
import slick.jdbc
import slick.jdbc.JdbcBackend.Database

import java.util.concurrent.Executors
import scala.concurrent.duration.Duration
import scala.concurrent.{ExecutionContext, ExecutionContextExecutor}

trait PostgresContainer extends ForAllTestContainer {
  self: Suite =>

  val logger: Logger = LoggerFactory.getLogger(classOf[PostgresContainer])

  implicit val testExecutionContext: ExecutionContextExecutor =
    ExecutionContext.fromExecutor(Executors.newFixedThreadPool(6))

  override val container: PostgreSQLContainer = PostgreSQLContainer(
    //    dockerImageNameOverride = "postgres:14.2",
    databaseName = "meso-alert-test",
    username = "meso-alert-test",
    password = "meso-alert-test")

  implicit def database: jdbc.JdbcBackend.DatabaseDef = {
    logger.info(s"Returning database definition from url ${container.jdbcUrl}")
    Database.forURL(url = container.jdbcUrl, user = container.username,
      password = container.password, driver = container.driverClassName)
  }

  implicit val timeout: Duration = Duration(1, "min")
//  implicit val ec: ExecutionContextExecutor = ExecutionContext.global

}
