package mirra

import cats.effect.Async
import cats.implicits.*
import cats.~>
import doobie.Transactor
import doobie.free.connection
import doobie.free.connection.ConnectionIO
import doobie.util.transactor.Strategy

object DoobieSupport {
  def commitTrans[F[_] : {Async}](driver: String, jdbcUrl: String, username: String, password: String): ConnectionIO ~> F =
    Transactor.fromDriverManager[F](
      driver = driver,
      url    = jdbcUrl,
      user   = username,
      password = password,
      logHandler = None
    ).trans

  def rollbackTrans[F[_] : {Async}](driver: String, jdbcUrl: String, username: String, password: String): ConnectionIO ~> F = {
    val transactor = {
      def tx = Transactor.fromDriverManager[F](
        driver = driver,
        url = jdbcUrl,
        user = username,
        password = password,
        logHandler = None
      )

      Transactor.strategy.modify(
        tx,
        _ => Strategy(
          before = connection.setAutoCommit(false),
          after = connection.unit,
          oops = connection.unit, always = connection.rollback *> connection.close
        )
      )
    }

    transactor.trans
  }
}
