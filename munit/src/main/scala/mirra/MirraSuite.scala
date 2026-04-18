package mirra

import cats.{Functor, Monad}
import cats.data.Tuple2K
import cats.effect.Resource
import cats.effect.kernel.MonadCancelThrow
import cats.implicits.*
import cats.tagless.SemigroupalK
import munit.{CatsEffectSuite, Compare, Location, ScalaCheckEffectSuite}

trait MirraSuite[OutsideWorldEffect[_] : MonadCancelThrow, Algebra[_[_]] : SemigroupalK] extends CatsEffectSuite with ScalaCheckEffectSuite {

    type BootstrapContext

    type MirraState
    type MirraEffect[A] = Mirra[MirraState, A]
    type TransactionEffect[_]
    type PairedEffect[A] = Tuple2K[TransactionEffect, MirraEffect, A]

    type SUT = SystemUnderTest[Algebra, OutsideWorldEffect, TransactionEffect, MirraState]

    def bootstrapSystemUnderTest(context: BootstrapContext): Resource[OutsideWorldEffect, SUT]

    def assertMirroring[A](c: BootstrapContext)(f: SUT#Paired => PairedEffect[A])(implicit C: Compare[A, A], L: Location): OutsideWorldEffect[Unit] =
      bootstrapSystemUnderTest(c).use(_.eval(f).map { case (left, right) => assertEquals(left, right) })

}
