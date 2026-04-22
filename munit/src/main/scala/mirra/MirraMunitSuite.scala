package mirra

import cats.data.Tuple2K
import cats.effect.Resource
import cats.effect.kernel.MonadCancelThrow
import cats.implicits.*
import cats.tagless.SemigroupalK
import cats.~>
import munit.{CatsEffectSuite, Compare, Location, ScalaCheckEffectSuite}

/** munit + cats-effect integration for Mirra mirror-testing.
  *
  * `MirraMunitSuite` wires [[munit.CatsEffectSuite]] and [[munit.ScalaCheckEffectSuite]]
  * together with Mirra's mirror-testing machinery.  The generic effect type `F[_]`
  * (bounded by `MonadCancelThrow`) lets you use any Cats Effect–compatible runtime
  * (typically `IO`).
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
  * asserts the two results are equal.
  *
  * Constructing a [[SystemUnderTest]] requires four things:
  *   - `initState: MirraState` — the starting in-memory state (e.g. `Universe.zero`).
  *   - `db: Alg[TransactionEffect]` — the real database interpreter.
  *   - `model: Alg[MirraEffect]` — the Mirra model interpreter.
  *   - `tx: TransactionEffect ~> F` — a natural transformation that runs (and rolls back)
  *     a single `TransactionEffect` action in `F`.  Use `DoobieSupport.rollbackTrans` or
  *     `SkunkSupport.rollbackTrans` for the built-in helpers.
  *
  * == How to implement ==
  *
  * Extend this trait in your test class and follow these steps:
  *
  *   1. '''Supply three abstract types'''
  *      - `BootstrapContext` — the infrastructure value your test framework hands to each
  *        test (e.g. a running `PostgreSQLContainer`).
  *      - `MirraState` — the in-memory domain state threaded through the model interpreter
  *        (e.g. `Universe`).
  *      - `TransactionEffect[_]` — the real database effect monad
  *        (e.g. `ConnectionIO` for Doobie, `Kleisli[F, Session[F], *]` for Skunk).
  *
  *   2. '''Implement `bootstrapSystemUnderTest`'''
  *      Return a `Resource[F, SystemUnderTest]` that acquires your database connection /
  *      transactor and constructs a `new SystemUnderTest(initState, db, model, tx)`.
  *      Wrap it in `Resource.pure(...)` when setup is already managed externally (e.g. by
  *      `TestContainerForAll`).
  *
  *   3. '''Write property tests'''
  *      Use `PropF.forAllF` from scalacheck-effect inside munit `test(...)` blocks, and
  *      call `assertMirroring(context) { x => … }` to exercise the algebra.  `x` is
  *      `Alg[PairedEffect]` — your algebra running both interpreters simultaneously.
  *
  * {{{
  * class DoobiePersonRepositorySpec
  *     extends MirraMunitSuite[IO, PersonRepository]
  *     with TestContainerForAll {
  *
  *   // Step 1: abstract types
  *   override type BootstrapContext     = Containers
  *   override type MirraState           = Universe
  *   override type TransactionEffect[A] = ConnectionIO[A]
  *
  *   // Step 2: SystemUnderTest construction
  *   override def bootstrapSystemUnderTest(c: Containers): Resource[IO, SystemUnderTest] =
  *     Resource.pure(new SystemUnderTest(
  *       Universe.zero,
  *       DoobiePersonRepository,
  *       MirraPersonRepository,
  *       DoobieSupport.rollbackTrans[IO]("org.postgresql.Driver", c.jdbcUrl, c.username, c.password)
  *     ))
  *
  *   // Step 3: property tests
  *   test("insert and read") {
  *     PropF.forAllF { (persons: List[Person]) =>
  *       withContainers { container =>
  *         assertMirroring(container) { x =>
  *           for {
  *             _ <- x.create
  *             _ <- x.insertMany(persons)
  *             r <- x.listAll()
  *           } yield r
  *         }
  *       }
  *     }
  *   }
  * }
  * }}}
  */
trait MirraMunitSuite[F[_]: MonadCancelThrow, Alg[_[_]]: SemigroupalK] extends CatsEffectSuite with ScalaCheckEffectSuite {

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
    * See the [[MirraMunitSuite]] scaladoc for a full explanation of the constructor arguments.
    */
  final class SystemUnderTest(initState: MirraState, db: Alg[TransactionEffect], model: Alg[MirraEffect], tx: TransactionEffect ~> F) {
    type Paired = Alg[PairedEffect]
    private val paired = SemigroupalK[Alg].productK(db, model)

    /** Run `f` against both interpreters and return `(realResult, modelResult)`. */
    def eval[A](f: Paired => PairedEffect[A]): F[(A, A)] = {
      val eff = f(paired)
      tx(eff.first).map(_ -> eff.second.run(initState))
    }
  }

  /** Allocate the [[SystemUnderTest]] for a given bootstrap context.
    * Wrap in `Resource.pure(...)` when the connection is already managed externally
    * (e.g. by `TestContainerForAll`).
    */
  def bootstrapSystemUnderTest(c: BootstrapContext): Resource[F, SystemUnderTest]

  /** Run `f` against both interpreters and assert the results are equal.
    *
    * A failed equality check surfaces the two differing values via munit's `assertEquals`.
    *
    * == Approximate equality ==
    *
    * The comparison is driven by the implicit `Compare[A, A]` instance.  When exact structural
    * equality is too strict — for example, when the real database returns floating-point
    * coordinates that differ from the in-memory model by a rounding error — supply a custom
    * `Compare` instance in scope:
    *
    * {{{
    * given Compare[Location, Location] with
    *   def isEqual(a: Location, b: Location): Boolean =
    *     math.abs(a.lat - b.lat) < 1e-6 && math.abs(a.lng - b.lng) < 1e-6
    * }}}
    *
    * munit's `assertEquals` will pick up this instance automatically.
    */
  def assertMirroring[A](c: BootstrapContext)(f: SystemUnderTest#Paired => PairedEffect[A])(implicit C: Compare[A, A], L: Location): F[Unit] =
    bootstrapSystemUnderTest(c).use(_.eval(f).map { case (left, right) => assertEquals(left, right) })

}
