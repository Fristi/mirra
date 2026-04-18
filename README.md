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

Two integrations are provided out of the box.

#### Doobie

The effect type is `ConnectionIO` — Doobie's own connection action type.

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

#### Skunk

The effect type is `Kleisli[F, Session[F], *]` — a function from a live Skunk session to an `F` action. All composed operations share the same session, so they run in the same transaction.

```scala
import cats.data.Kleisli
import cats.effect.Async
import cats.implicits.*
import skunk.*
import skunk.codec.all.*
import skunk.data.Completion
import skunk.implicits.*

class SkunkPersonRepository[F[_]: Async] extends PersonRepository[[A] =>> Kleisli[F, Session[F], A]] {

  private val personCodec: Codec[Person] =
    (uuid ~ text ~ int4).imap { case id ~ name ~ age => Person(id, name, age) }(p =>
      p.id ~ p.name ~ p.age
    )

  private val createCommand: Command[Void] =
    sql"""
      CREATE TABLE IF NOT EXISTS persons (
        id uuid PRIMARY KEY,
        name text NOT NULL,
        age int4 NOT NULL
      )
    """.command

  private val insertCommand: Command[Person] =
    sql"INSERT INTO persons (id, name, age) VALUES ($personCodec)".command

  private val listAllQuery: Query[Void, Person] =
    sql"SELECT id, name, age FROM persons".query(personCodec)

  private val deleteOlderThanCommand: Command[Long] =
    sql"DELETE FROM persons WHERE age > ${int8}".command

  def create: Kleisli[F, Session[F], Unit] =
    Kleisli(_.execute(createCommand).void)

  def insertMany(persons: List[Person]): Kleisli[F, Session[F], Long] = Kleisli { session =>
    session.prepareR(insertCommand).use(pc => persons.traverse(pc.execute).map(_.length.toLong))
  }

  def deleteWhenOlderThen(age: Long): Kleisli[F, Session[F], Long] = Kleisli { session =>
    session.prepareR(deleteOlderThanCommand).use(_.execute(age).map {
      case Completion.Delete(n) => n.toLong
      case _                   => 0L
    })
  }

  def listAll(): Kleisli[F, Session[F], List[Person]] =
    Kleisli(_.execute(listAllQuery))
}
```

### 4. Mirror-test them

#### Doobie

`DoobieSupport.rollbackTrans` returns a `ConnectionIO ~> F` natural transformation that runs each test inside a transaction and always rolls back, leaving the database clean between property iterations.

```scala
class DoobiePersonRepositorySpec
    extends CatsEffectSuite
    with ScalaCheckEffectSuite
    with MirraSuite[IO]
    with TestContainerForAll {

  given Arbitrary[Person] = Arbitrary { /* ... */ }

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
            x.create *> x.insertMany(persons) *> x.listAll()
          }
        }
      }
    }
  }
}
```

#### Skunk

`SkunkSupport.rollbackTrans` does the same for Skunk: opens a session, begins a transaction, and always rolls back. It returns a `Kleisli[F, Session[F], *] ~> F` nat-trans directly, so the test body is identical in structure. Noop otel4s tracing is wired internally — no setup required.

```scala
class SkunkPersonRepositorySpec
    extends CatsEffectSuite
    with ScalaCheckEffectSuite
    with MirraSuite[IO]
    with TestContainerForAll {

  given Arbitrary[Person] = Arbitrary { /* ... */ }

  override val containerDef: PostgreSQLContainer.Def = /* same as Doobie */

  test("should insert and read") {
    PropF.forAllF { (persons: List[Person]) =>
      withContainers { (c: Containers) =>
        SkunkSupport.rollbackTrans[IO](
          host     = c.container.getHost,
          port     = c.container.getMappedPort(5432),
          user     = c.username,
          database = c.databaseName,
          password = Some(c.password)
        ).use { trans =>
          def algebraUnderTest =
            new AlgebraUnderTest[PersonRepository, IO, [A] =>> Kleisli[IO, Session[IO], A], Universe](
              Universe.zero, SkunkPersonRepository[IO], MirraPersonRepository, trans
            )

          assertMirroring {
            algebraUnderTest.model.eval { x =>
              x.create *> x.insertMany(persons) *> x.listAll()
            }
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

## Modules

| Module | What it provides |
|---|---|
| `core` | `Mirra[S, A]`, `AlgebraUnderTest`, `MirraSyntax` |
| `munit` | `MirraSuite[F]` — mix into munit suites |
| `doobie` | `DoobieSupport.rollbackTrans` — `ConnectionIO ~> F` with always-rollback |
| `skunk` | `SkunkSupport.rollbackTrans` — `Kleisli[F, Session[F], *] ~> F` with always-rollback |

## Dependencies

Built with Scala 3.

**Core:** [cats-tagless](https://github.com/typelevel/cats-tagless) (FunctorK / SemigroupalK derivation), [Monocle](https://github.com/optics-dev/Monocle) (lenses for state manipulation).

**munit module:** [munit](https://scalameta.org/munit/), [munit-cats-effect](https://github.com/typelevel/munit-cats-effect), [scalacheck-effect-munit](https://github.com/typelevel/scalacheck-effect).

**doobie module:** [Doobie](https://github.com/tpolecat/doobie).

**skunk module:** [Skunk](https://github.com/tpolecat/skunk) 1.0.0.

**Example module:** Doobie + Skunk, [Testcontainers](https://www.testcontainers.org/) (PostgreSQL), [ScalaCheck](https://scalacheck.org/).

## Inspiration

This library implements the [test oracle](https://fsharpforfunandprofit.com/posts/property-based-testing-2/#test-oracle) pattern described by Scott Wlaschin, applied to tagless final algebras: maintain a simplified model alongside the system under test, apply the same operations to both, and compare final states.

## License

This project is archived. Feel free to use it as a reference or fork it for your own needs.
