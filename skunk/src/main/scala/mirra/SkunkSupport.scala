package mirra

import cats.data.Kleisli
import cats.effect.{Async, Resource}
import cats.effect.std.Console
import cats.implicits.*
import cats.~>
import fs2.io.net.Network
import org.typelevel.otel4s.metrics.Meter
import org.typelevel.otel4s.trace.Tracer
import skunk.Session

type SkunkTransaction[F[_], A] = Kleisli[F, Session[F], A]

object SkunkSupport {
  def rollbackTrans[F[_]: {Async, Console, Network}](
    host: String,
    port: Int = 5432,
    user: String,
    database: String,
    password: Option[String] = None
  ): Resource[F, ([A] =>> SkunkTransaction[F, A]) ~> F] = {
    given Tracer[F] = Tracer.noop
    given Meter[F] = Meter.noop

    Session.single[F](
      host = host,
      port = port,
      user = user,
      database = database,
      password = password
    ).flatMap { session =>
      Resource
        .make(session.transaction.allocated.map { case (tx, _) => (session, tx) })(
          { case (_, tx) => tx.rollback.void }
        )
        .map { case (session, _) =>
          new (([A] =>> SkunkTransaction[F, A]) ~> F):
            def apply[A](fa: Kleisli[F, Session[F], A]): F[A] = fa.run(session)
        }
    }
  }
}
