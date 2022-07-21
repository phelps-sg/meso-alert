## Contribution guidelines

- Please follow [Scala best practices](https://github.com/alexandru/scala-best-practices).
- All code should have accompanying tests written using [scalatest](https://www.scalatest.org/user_guide)
  and [scalamock](https://scalamock.org/user-guide/).
- Before pushing commits to the scala code, run [scalafix](https://scalacenter.github.io/scalafix/) in an sbt shell, or alternatively from bash:
~~~bash
make sbt-scalafix
~~~
- Before pushing commits to [docker/meso-alert.docker](docker/meso-alert.docker), ensure that the play application server can be built and run using:
~~~bash
make docker-server-start
~~~
- Before pushing commits to [docker/meso-alert-ci.docker](docker/meso-alert-ci.docker) rebuild the image and push to the container registry using
~~~bash
make docker-ci-push
~~~