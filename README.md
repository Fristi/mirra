# mirra

**Mirror-test your tagless final algebras in Scala.**

Mirra verifies that a real repository implementation behaves the same way as a simple in-memory model — using property-based testing to catch the bugs you'd never think to write a case for.

> **Status:** This project is not active and was a proof-of-concept. It may still be useful as a reference or starting point.

## The problem: duplicated expectations

When you property-test a repository directly, you end up re-implementing its logic in your assertions. Suppose you're testing a `deleteWhenOlderThen` method. Your property test generates random persons and a random age threshold, calls the repository, and then you need to assert the right people were deleted. To do that, you filter the generated input list yourself:

```scala
prop { (persons: List[Person], age: Int) =>
  repo.insertMany(persons)
  repo.deleteWhenOlderThen(age)
  val remaining = repo.listAll()

  // You're re-implementing the repository's filter logic right here in your test
  remaining must_== persons.filter(_.age <= age)
}
```

This is fragile. Your assertion duplicates the exact filtering logic the repository is supposed to implement. If you get the assertion wrong (off-by-one, wrong comparison operator, edge case), the test is worthless — and you won't know it. You've encoded your expectations twice: once in the implementation, once in the test, and you're hoping they match.

## The solution: make the expectations an executable model

Instead of scattering filtering logic across assertions, **move it into a proper in-memory implementation** of the same algebra. This model is trivially simple — just list operations on a case class — so it's easy to get right. Then run the same operations against both the real implementation and the model, and compare results.

This is the [test oracle](https://fsharpforfunandprofit.com/posts/property-based-testing-2/#test-oracle) pattern: you don't assert _what_ the result should be, you assert that two implementations _agree_.

The expectations now live in one place (the model), they're a real runnable implementation rather than ad-hoc assertions, and every property test is just: "does the real thing do the same as the model?"

```
  Generate random data
            │
    ┌───────┴───────┐
    ▼               ▼
 ┌──────┐     ┌──────────┐
 │ Real │     │ In-memory│
 │ impl │     │  model   │
 │(DB)  │     │ (Mirra)  │
 └──┬───┘     └────┬─────┘
    │              │
    ▼              ▼
  result₁ ═══ result₂ ?
```

If they diverge, either the real implementation has a bug, or the model is wrong — both of which are valuable to discover.

### Why this also helps your service tests

Once you've proven the in-memory model is faithful to the real implementation, you can use that model as a drop-in replacement in your service-layer unit tests. No database, no containers, no network — just fast, deterministic tests that you _know_ are behaviorally accurate, because the model has been validated against the real thing.

This is much better than mocks: a mock returns whatever you tell it to, even outputs the real implementation would never produce for a given input. A validated in-memory model can't lie that way.

## How it works

1. **Define** a tagless final algebra for your repository.
2. **Implement** it for real — against a database, HTTP API, etc.
3. **Model** it with `Mirra[S, *]`, a specialized `State` monad with built-in CRUD helpers (`insertMany`, `delete`, `all`, etc.) that operate over a simple in-memory state `S` using Monocle lenses.
4. **Wire** both into an `AlgebraUnderTest`, which uses `FunctorK` / `SemigroupalK` (from cats-tagless) to run the same program against both interpreters.
5. **Assert mirroring** — for any randomly generated input, both must produce the same result.

### What properties fall out of this?

| Property | What it catches |
|---|---|
| **Data loss** | Insert → read doesn't return everything that was inserted |
| **Locality** | A delete/update affects records it shouldn't (or misses ones it should) |
| **Idempotency** | Applying an operation twice changes the result vs. applying it once |

You don't need to encode these properties manually. They emerge naturally from mirroring: if the real implementation drops a record, reorders something, or over-deletes, the model will disagree.

## Example

### 1. Define the algebra

```scala
import cats.tagless.{Derive, FunctorK, SemigroupalK}
import java.util.UUID

final case class Person(id: UUID, name: String, age: Int)

trait PersonRepository[F[_]] {
  def create: F[Unit]
  def insertMany(persons: List[Person]): F[Long]
  def deleteWhenOlderThen(age: Long): F[Long]
  def listAll(): F[List[Person]]
}

object PersonRepository {
  // These let AlgebraUnderTest run both interpreters through one algebra
  implicit val functorK: FunctorK[PersonRepository] = Derive.functorK
  implicit val semigroupalK: SemigroupalK[PersonRepository] = Derive.semigroupalK
}
```

### 2. Write the in-memory model

Define a "universe" — a case class holding your state — and implement the algebra using Mirra's CRUD helpers with Monocle `Focus` lenses.

```scala
import monocle.Focus

final case class Universe(persons: List[Person])

object Universe {
  def zero: Universe = Universe(Nil)
}

object MirraPersonRepository extends PersonRepository[[A] =>> Mirra[Universe, A]] {
  def create: Mirra[Universe, Unit] =
    Mirra.unit

  def insertMany(persons: List[Person]): Mirra[Universe, Long] =
    Mirra.insertMany(Focus[Universe](_.persons))(persons)

  def deleteWhenOlderThen(age: Long): Mirra[Universe, Long] =
    Mirra.delete(Focus[Universe](_.persons))(_.age > age)

  def listAll(): Mirra[Universe, List[Person]] =
    Mirra.all(Focus[Universe](_.persons))
}
```

This is your model — the single source of truth for expected behavior. It's so simple (append to a list, filter a list, return a list) that it's hard to get wrong.

### 3. Write the real implementation

```scala
import doobie._
import doobie.implicits._

object DoobiePersonRepository extends PersonRepository[ConnectionIO] {

  object queries {
    def create =
      fr"""create table if not exists persons (
        |  id uuid primary key,
        |  name varchar(50) not null,
        |  age numeric not null
        |)""".stripMargin.update

    def deleteWhenOlderThen(age: Long): Update0 =
      fr"delete from persons where age > $age".update

    def listAll: Query0[Person] =
      fr"select id, name, age from persons".query[Person]
  }

  def create: ConnectionIO[Unit] =
    queries.create.run.void

  def insertMany(persons: List[Person]): ConnectionIO[Long] =
    Update[Person]("insert into persons (id, name, age) values (?, ?, ?)")
      .updateMany(persons).map(_.toLong)

  def deleteWhenOlderThen(age: Long): ConnectionIO[Long] =
    queries.deleteWhenOlderThen(age).run.map(_.toLong)

  def listAll(): ConnectionIO[List[Person]] =
    queries.listAll.to[List]
}
```

### 4. Mirror-test them

```scala
import cats.effect.IO
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import org.testcontainers.utility.DockerImageName

class PersonRepoSpec
    extends CatsEffectSuite
    with ScalaCheckEffectSuite
    with MirraSuite[IO]
    with TestContainerForAll {

  given Arbitrary[Person] = Arbitrary {
    for {
      id   <- Gen.uuid
      name <- Gen.stringOfN(50, Gen.alphaChar)
      age  <- Gen.posNum[Int]
    } yield Person(id, name, age)
  }

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def(
    dockerImageName = DockerImageName.parse("postgres:15.1"),
    databaseName    = "testcontainer-scala",
    username        = "scala",
    password        = "scala"
  )

  test("should insert and read") {
    PropF.forAllF { (persons: List[Person]) =>
      withContainers { (c: Containers) =>
        val trans = DoobieSupport.rollbackTrans[IO](
          "org.postgresql.Driver", c.jdbcUrl, c.username, c.password
        )

        def algebraUnderTest =
          new AlgebraUnderTest(Universe.zero, DoobiePersonRepository, MirraPersonRepository, trans)

        assertMirroring {
          algebraUnderTest.model.eval { x =>
            x.create *>
              x.insertMany(persons) *>
              x.listAll()
          }
        }
      }
    }
  }

  test("should delete people older then") {
    PropF.forAllF { (persons: List[Person], age: Int) =>
      withContainers { (c: Containers) =>
        val trans = DoobieSupport.rollbackTrans[IO](
          "org.postgresql.Driver", c.jdbcUrl, c.username, c.password
        )

        def algebraUnderTest =
          new AlgebraUnderTest(Universe.zero, DoobiePersonRepository, MirraPersonRepository, trans)

        assertMirroring {
          algebraUnderTest.model.eval { x =>
            x.create *>
              x.insertMany(persons) *>
              x.deleteWhenOlderThen(age) *>
              x.listAll()
          }
        }
      }
    }
  }
}
```

Notice there's no assertion logic about _what_ the result should be. No filtering, no manual comparison. Just: "do both implementations agree?" ScalaCheck generates the inputs, the harness runs both, `assertMirroring` diffs the outputs.

## Key concepts

**`Mirra[S, A]`** — A `State`-like monad with built-in helpers for modeling CRUD operations (`insertMany`, `delete`, `all`, `unit`, etc.). Uses Monocle lenses to target collections within your state type `S`. This is where your expected behavior lives — in one place, as a real implementation, not scattered across test assertions.

**`AlgebraUnderTest[Alg, F, Tx, S]`** — Wires together a real implementation (`Alg[Tx]`) and a model (`Alg[Mirra[S, *]]`), using `FunctorK` / `SemigroupalK` to run both through the same algebra and compare results. `Tx ~> F` is a natural transformation (e.g. a Doobie transactor) that interprets the real effect into `F`.

**`assertMirroring`** — Executes the program against both interpreters, diffs the results, and fails the test if they diverge.

**`MirraSuite[F[_]]`** — A munit trait providing `assertMirroring`. Mix it into your test suite alongside `CatsEffectSuite` and `ScalaCheckEffectSuite`.

**`FunctorK` / `SemigroupalK`** — Type classes from [cats-tagless](https://github.com/typelevel/cats-tagless) that allow transforming the effect type of an algebra. These are what make it possible to run a single program against two different interpreters. Derived automatically with `Derive.functorK` / `Derive.semigroupalK`.

## Dependencies

Built with Scala 3.

**Core:** [cats-tagless](https://github.com/typelevel/cats-tagless) (FunctorK / SemigroupalK derivation), [Monocle](https://github.com/optics-dev/Monocle) (lenses for state manipulation).

**munit module:** [munit](https://scalameta.org/munit/), [munit-cats-effect](https://github.com/typelevel/munit-cats-effect), [scalacheck-effect-munit](https://github.com/typelevel/scalacheck-effect).

**Example module:** [Doobie](https://github.com/tpolecat/doobie), [Testcontainers](https://www.testcontainers.org/) (PostgreSQL), [ScalaCheck](https://scalacheck.org/).

## Inspiration

This library implements the [test oracle](https://fsharpforfunandprofit.com/posts/property-based-testing-2/#test-oracle) pattern described by Scott Wlaschin, applied to tagless final algebras: maintain a simplified model alongside the system under test, apply the same operations to both, and compare final states.

## License

This project is archived. Feel free to use it as a reference or fork it for your own needs.
