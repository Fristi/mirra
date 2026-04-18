package mirra

import cats.data.Tuple2K
import cats.implicits.*
import cats.tagless.SemigroupalK
import cats.{Functor, ~>}

class AlgebraUnderTest[Alg[_[_]], F[_], Tx[_], D](initState: D, db: Alg[Tx], model: Alg[[X] =>> Mirra[D, X]], tx: Tx ~> F) {

  private type Mirra_[A] = Mirra[D, A]
  private type Eff[A] = Tuple2K[Tx, Mirra_, A]
  private type Paired = Alg[Eff]

  trait Evaluator {
    def eval[A](f: Paired => Eff[A]): F[(A, A)]
  }

  def model(implicit S: SemigroupalK[Alg], F: Functor[F]): Evaluator = {
    val paired: Paired = S.productK(db, model)
    new Evaluator {
      override def eval[A](f: Paired => Eff[A]): F[(A, A)] = {
        //here we get the `Tuple2K` from `f`
        val effectTuple: Eff[A] = f(paired)
        //we run the connection against a rollback transactor, and get the result
        val dbValue: F[A] = tx(effectTuple.first)
        //we run the state monad and get the value
        val stateValue: A = effectTuple.second.run(initState)

        dbValue.map(_ -> stateValue)
      }
    }
  }
}