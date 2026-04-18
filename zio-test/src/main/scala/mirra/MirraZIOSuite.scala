package mirra

import cats.data.Tuple2K
import cats.tagless.SemigroupalK
import cats.~>
import zio.*
import zio.test.*

/** ZIO Test integration for Mirra mirror-testing.
  *
  * Mirrors [[mirra.MirraSuite]] but targets ZIO Test instead of munit/cats-effect.
  * The effect type is fixed to [[Task]] (`ZIO[Any, Throwable, *]`); bridging to
  * Cats Effect (e.g. for Doobie) is done in concrete suites via `zio-interop-cats`.
  *
  * Concrete suites extend this trait as an `object`, supply three abstract types,
  * implement [[bootstrapSystemUnderTest]], and define `spec` using the helpers:
  *
  * {{{
  * object MySpec extends MirraZIOSuite[PersonRepository] {
  *   override type BootstrapContext    = PostgreSQLContainer
  *   override type MirraState         = Universe
  *   override type TransactionEffect[A] = ConnectionIO[A]
  *
  *   override def bootstrapSystemUnderTest(c: PostgreSQLContainer): ZIO[Scope, Throwable, SystemUnderTest] = …
  *
  *   def spec = suite("MySpec")(
  *     test("insert and read") {
  *       ZIO.serviceWithZIO[PostgreSQLContainer] { container =>
  *         check(Gen.listOf(genPerson)) { persons =>
  *           assertMirroring(container) { x =>
  *             for {
  *               _ <- x.create
  *               _ <- x.insertMany(persons)
  *               r <- x.listAll()
  *             } yield r
  *           }
  *         }
  *       }
  *     }
  *   ).provideShared(containerLayer)
  * }
  * }}}
  */
trait MirraZIOSuite[Alg[_[_]]: SemigroupalK] extends ZIOSpecDefault {

  /** Context object produced by test-infrastructure bootstrap (e.g. a running container). */
  type BootstrapContext

  /** In-memory domain state threaded through the [[Mirra]] model interpreter. */
  type MirraState

  /** The real database effect (e.g. `ConnectionIO` for Doobie, `Kleisli[F, Session[F], *]` for Skunk). */
  type TransactionEffect[_]

  type MirraEffect[A]  = Mirra[MirraState, A]
  type PairedEffect[A] = Tuple2K[TransactionEffect, MirraEffect, A]

  /** Pairs the real database interpreter with the in-memory Mirra model and evaluates programs against both. */
  final class SystemUnderTest(
    initState: MirraState,
    db: Alg[TransactionEffect],
    model: Alg[MirraEffect],
    tx: TransactionEffect ~> Task,
  ) {
    type Paired = Alg[PairedEffect]
    private val paired: Paired = SemigroupalK[Alg].productK(db, model)

    /** Run `f` against both interpreters and return `(realResult, modelResult)`. */
    def eval[A](f: Paired => PairedEffect[A]): Task[(A, A)] = {
      val eff = f(paired)
      tx(eff.first).map(_ -> eff.second.run(initState))
    }
  }

  /** Allocate the [[SystemUnderTest]] for a given bootstrap context.
    * Use [[ZIO.acquireRelease]] / `ZLayer.scoped` for resources that need cleanup.
    */
  def bootstrapSystemUnderTest(c: BootstrapContext): ZIO[Scope, Throwable, SystemUnderTest]

  /** Run `f` against both interpreters and assert the results are equal.
    *
    * A failed equality check surfaces the two differing values via ZIO Test's
    * `assertTrue` macro, which provides a readable diff in the test report.
    */
  def assertMirroring[A](c: BootstrapContext)(f: SystemUnderTest#Paired => PairedEffect[A]): Task[TestResult] =
    ZIO.scoped {
      bootstrapSystemUnderTest(c).flatMap { sut =>
        sut.eval(f).map { case (real, model) =>
          assertTrue(real == model)
        }
      }
    }
}
