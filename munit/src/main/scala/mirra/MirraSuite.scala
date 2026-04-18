package mirra

import cats.data.Tuple2K
import cats.effect.Resource
import cats.effect.kernel.MonadCancelThrow
import cats.implicits.*
import cats.tagless.SemigroupalK
import cats.~>
import munit.{CatsEffectSuite, Compare, Location, ScalaCheckEffectSuite}

trait MirraSuite[F[_]: MonadCancelThrow, Alg[_[_]]: SemigroupalK] extends CatsEffectSuite with ScalaCheckEffectSuite {

  type BootstrapContext
  type MirraState
  type TransactionEffect[_]

  type MirraEffect[A] = Mirra[MirraState, A]
  type PairedEffect[A] = Tuple2K[TransactionEffect, MirraEffect, A]

  final class SystemUnderTest(initState: MirraState, db: Alg[TransactionEffect], model: Alg[MirraEffect], tx: TransactionEffect ~> F) {
    type Paired = Alg[PairedEffect]
    private val paired = SemigroupalK[Alg].productK(db, model)

    def eval[A](f: Paired => PairedEffect[A]): F[(A, A)] = {
      val eff = f(paired)
      tx(eff.first).map(_ -> eff.second.run(initState))
    }
  }

  def bootstrapSystemUnderTest(c: BootstrapContext): Resource[F, SystemUnderTest]

  def assertMirroring[A](c: BootstrapContext)(f: SystemUnderTest#Paired => PairedEffect[A])(implicit C: Compare[A, A], L: Location): F[Unit] =
    bootstrapSystemUnderTest(c).use(_.eval(f).map { case (left, right) => assertEquals(left, right) })

}
