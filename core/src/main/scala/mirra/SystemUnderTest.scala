package mirra

import cats.data.Tuple2K
import cats.implicits.*
import cats.tagless.SemigroupalK
import cats.{Functor, ~>}

class SystemUnderTest[Alg[_[_]] : SemigroupalK, F[_] : Functor, Tx[_], D](initState: D, db: Alg[Tx], model: Alg[[X] =>> Mirra[D, X]], tx: Tx ~> F) {

  private type Mirra_[A] = Mirra[D, A]
  private type Eff[A] = Tuple2K[Tx, Mirra_, A]

  type Paired = Alg[Eff]

  private val paired = SemigroupalK[Alg].productK(db, model)
  
  def eval[A](f: Paired => Eff[A]): F[(A, A)] = {
    //here we get the `Tuple2K` from `f`
    val effectTuple: Eff[A] = f(paired)
    //we run the connection against a rollback transactor, and get the result
    val dbValue: F[A] = tx(effectTuple.first)
    //we run the state monad and get the value
    val stateValue: A = effectTuple.second.run(initState)

    dbValue.map(_ -> stateValue)
  }
}