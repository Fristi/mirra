package mirra

import cats.tagless.FunctorK
import cats.~>
import zio.*

/** Constructs a ZIO `ULayer` for an `Alg` backed by an in-memory `Ref`.
  *
  * Each algebra method executes the underlying `State[D, A]` atomically
  * against a `Ref[D]`, turning `Alg[[A] =>> Mirra[D, A]]` into `Alg[Task]`.
  */
object MirraZIO {

  def layer[Alg[_[_]]: FunctorK, D](
      alg: Alg[[A] =>> Mirra[D, A]],
      initialState: D,
  )(using Tag[Alg[Task]]): ULayer[Alg[Task]] =
    ZLayer.fromZIO(
      Ref.make(initialState).map { ref =>
        val nt: ([A] =>> Mirra[D, A]) ~> Task = new (([A] =>> Mirra[D, A]) ~> Task) {
          def apply[A](fa: Mirra[D, A]): Task[A] =
            ref.modify { state =>
              val (newState, result) = fa.db.run(state).value
              (result, newState)
            }
        }
        FunctorK[Alg].mapK(alg)(nt)
      }
    )
}
