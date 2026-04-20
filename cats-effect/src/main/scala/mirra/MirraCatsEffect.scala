package mirra

import cats.effect.{Ref, Sync}
import cats.tagless.FunctorK
import cats.~>

/** Constructs an in-memory `Alg[F]` backed by a `Ref[D]`.
  *
  * Each algebra method executes the underlying `State[D, A]` atomically
  * against a `Ref[D]`, turning `Alg[[A] =>> Mirra[D, A]]` into `Alg[F]`.
  */
object MirraCatsEffect {

  def make[F[_]: Sync, Alg[_[_]]: FunctorK, D](
      alg: Alg[[A] =>> Mirra[D, A]],
      initialState: D,
  ): F[Alg[F]] =
    Ref.of[F, D](initialState).map { ref =>
      val nt: ([A] =>> Mirra[D, A]) ~> F = new (([A] =>> Mirra[D, A]) ~> F) {
        def apply[A](fa: Mirra[D, A]): F[A] =
          ref.modify { state =>
            val (newState, result) = fa.db.run(state).value
            (newState, result)
          }
      }
      FunctorK[Alg].mapK(alg)(nt)
    }
}
