import javax.inject.Inject

import be.objectify.deadbolt.scala.filters.{AuthorizedRoute, AuthorizedRoutes, FilterConstraints}
import be.objectify.deadbolt.scala.filters._

class MyAuthorizedRoutes @Inject() (filterConstraints: FilterConstraints) extends AuthorizedRoutes {

  override val routes: Seq[AuthorizedRoute] =
    Seq(
      AuthorizedRoute(Get, "/", filterConstraints.subjectNotPresent, handler = None)
    )

}
