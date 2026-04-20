package mirra

import cats.data.Tuple2K
import cats.tagless.SemigroupalK
import cats.~>
import zio.*
import zio.test.*

/** ZIO Test integration for Mirra mirror-testing.
  *
  * `MirraZIOSuite` is the ZIO Test counterpart of [[mirra.MirraMunitSuite]].  The effect
  * type is fixed to [[Task]] (`ZIO[Any, Throwable, *]`); bridging to Cats Effect (e.g. for
  * Doobie) is done in concrete suites via `zio-interop-cats`.
  *
  * == What is a SystemUnderTest? ==
  *
  * A [[SystemUnderTest]] (SUT) holds two interpreters of your algebra running side-by-side:
  *
  *   - The '''real''' interpreter — your actual database-backed implementation
  *     (e.g. Doobie or Skunk).
  *   - The '''model''' interpreter — a pure in-memory [[Mirra]] implementation that acts
  *     as a reference oracle.
  *
  * Both interpreters are fused into a single `Alg[PairedEffect]` value using
  * `SemigroupalK.productK`.  When you call `eval(f)`, the same program `f` is threaded
  * through both at once, returning `(realResult, modelResult)`.  [[assertMirroring]] then
  * asserts the two results are equal using ZIO Test's `assertTrue` macro.
  *
  * Constructing a [[SystemUnderTest]] requires four things:
  *   - `initState: MirraState` — the starting in-memory state (e.g. `Universe.zero`).
  *   - `db: Alg[TransactionEffect]` — the real database interpreter.
  *   - `model: Alg[MirraEffect]` — the Mirra model interpreter.
  *   - `tx: TransactionEffect ~> Task` — a natural transformation that runs (and rolls back)
  *     a single `TransactionEffect` action as a `Task`.  Use `DoobieSupport.rollbackTrans`
  *     (lifted via `zio-interop-cats`) or `SkunkSupport.rollbackTrans` for built-in helpers.
  *
  * == How to implement ==
  *
  * Extend this trait as an `object`, follow these steps, and define `spec`:
  *
  *   1. '''Supply three abstract types'''
  *      - `BootstrapContext` — the infrastructure value produced by your ZLayer bootstrap
  *        (e.g. a running `PostgreSQLContainer`).
  *      - `MirraState` — the in-memory domain state threaded through the model interpreter
  *        (e.g. `Universe`).
  *      - `TransactionEffect[_]` — the real database effect monad
  *        (e.g. `ConnectionIO` for Doobie, `Kleisli[F, Session[F], *]` for Skunk).
  *
  *   2. '''Implement `bootstrapSystemUnderTest`'''
  *      Return a `ZIO[Scope, Throwable, SystemUnderTest]` that acquires your database
  *      connection / transactor and constructs a `new SystemUnderTest(initState, db, model, tx)`.
  *      Use `ZIO.acquireRelease` / `ZLayer.scoped` for resources that need cleanup.
  *
  *   3. '''Write property tests using ZIO Test'''
  *      Define `spec` using `suite(...)` / `test(...)`.  Inside each test, call
  *      `assertMirroring(context) { x => … }` where `x` is `Alg[PairedEffect]` — your
  *      algebra running both interpreters simultaneously.  Share expensive resources across
  *      tests with `.provideShared(layer)`.
  *
  * {{{
  * object MySpec extends MirraZIOSuite[PersonRepository] {
  *   override type BootstrapContext     = PostgreSQLContainer
  *   override type MirraState           = Universe
  *   override type TransactionEffect[A] = ConnectionIO[A]
  *
  *   // Step 2: SystemUnderTest construction
  *   override def bootstrapSystemUnderTest(c: PostgreSQLContainer): ZIO[Scope, Throwable, SystemUnderTest] = …
  *
  *   // Step 3: property tests
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

  /** Pairs the real database interpreter with the in-memory Mirra model and evaluates programs
    * against both simultaneously.
    *
    * See the [[MirraZIOSuite]] scaladoc for a full explanation of the constructor arguments.
    */
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
    *
    * == Approximate equality ==
    *
    * When exact structural equality is too strict — for example, when the real database returns
    * floating-point coordinates that differ from the in-memory model by a rounding error — use
    * the overload that accepts a custom equality function:
    *
    * {{{
    * assertMirroring(ctx)(f)((a, b) => math.abs(a - b) < 1e-6)
    * }}}
    *
    * For case classes, destructure in the lambda:
    *
    * {{{
    * assertMirroring(ctx)(f) { (a, b) =>
    *   math.abs(a.lat - b.lat) < 1e-6 && math.abs(a.lng - b.lng) < 1e-6
    * }
    * }}}
    */
  def assertMirroring[A](c: BootstrapContext)(f: SystemUnderTest#Paired => PairedEffect[A]): Task[TestResult] =
    assertMirroring(c)(f)(_ == _)

  /** Run `f` against both interpreters and assert the results are equal using a custom equality function.
    *
    * Use this overload when exact structural equality is too strict — for example, when comparing
    * floating-point values with a tolerance.
    *
    * @param eq a function returning `true` when the real and model results are considered equal
    */
  def assertMirroring[A](c: BootstrapContext)(f: SystemUnderTest#Paired => PairedEffect[A])(eq: (A, A) => Boolean): Task[TestResult] =
    ZIO.scoped {
      bootstrapSystemUnderTest(c).flatMap { sut =>
        sut.eval(f).map { case (real, model) =>
          assertTrue(eq(real, model))
        }
      }
    }
}
