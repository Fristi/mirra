package mirra

import cats.implicits._
import cats.effect.testing.specs2.CatsIO
import cats.effect.{Blocker, IO}
import com.softwaremill.diffx.Diff
import com.softwaremill.diffx.specs2.DiffMatcher
import doobie.{ExecutionContexts, Transactor}
import doobie.free.connection
import doobie.util.transactor.Strategy
import org.specs2.ScalaCheck
import org.specs2.mutable.Specification
import org.specs2.matcher.MatchResult

trait DoobieSpec
  extends ScalaCheck
    with ForAllTestContainer
    with UsesPostgreSQLMultipleDatabases
    with CatsIO
    with DiffMatcher { self: Specification =>

  def xa = Transactor.strategy.modify(Transactor.fromDriverManager[IO](
    driverName,
    jdbcUrl,
    dbUserName,
    dbPassword,
    Blocker.liftExecutionContext(ExecutionContexts.synchronous)
  ),
    _ => Strategy(before = connection.setAutoCommit(false), after = connection.unit, oops = connection.unit, always = connection.rollback *> connection.close)
  )

  def assertMirroring[A : Diff](tuple: IO[(A, A)]): IO[MatchResult[A]] =
    tuple.map { case (left, right) => left must matchTo(right) }
}
