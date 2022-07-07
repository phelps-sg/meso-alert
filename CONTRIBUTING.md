## Contribution guidelines

- Please follow [Scala best practices](https://github.com/alexandru/scala-best-practices).
- All code should have accompanying tests written using [scalatest](https://www.scalatest.org/user_guide)
  and [scalamock](https://scalamock.org/user-guide/).
- Before pushing commits, run [scalafix](https://scalacenter.github.io/scalafix/) in an sbt shell, or alternatively from bash:
~~~bash
make sbt-scalafix
~~~
