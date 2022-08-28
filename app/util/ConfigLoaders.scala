package util

import com.typesafe.config.Config
import play.api.ConfigLoader
import sttp.model.Uri

object ConfigLoaders {

  implicit val UriConfigLoader: ConfigLoader[Uri] = (config: Config, path: String) => {
    Uri.parse(config.getString(path)) match {
      case Right(domain) =>
        domain
      case Left(error) =>
        throw new RuntimeException(error)
    }
  }
}
