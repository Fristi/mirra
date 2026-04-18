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

  // Re-implementing the repository's filter logic right here in the test
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
4. **Wire** both into a `SystemUnderTest`, which uses `SemigroupalK` (from cats-tagless) to run the same program against both interpreters simultaneously.
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
  // These let SystemUnderTest run both interpreters through one algebra
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
  def create: ConnectionIO[Unit] =
    fr"""create table if not exists persons (
      |  id uuid primary key,
      |  name varchar(50) not null,
      |  age numeric not null
      |)""".stripMargin.update.run.void

  def insertMany(persons: List[Person]): ConnectionIO[Long] =
    Update[Person]("insert into persons (id, name, age) values (?, ?, ?)")
      .updateMany(persons).map(_.toLong)

  def deleteWhenOlderThen(age: Long): ConnectionIO[Long] =
    fr"delete from persons where age > $age".update.run.map(_.toLong)

  def listAll(): ConnectionIO[List[Person]] =
    fr"select id, name, age from persons".query[Person].to[List]
}
```

#### Skunk

The effect type is `Kleisli[F, Session[F], *]` — a function from a live Skunk session to an `F` action. All composed operations share the same session, so they run in the same transaction.

```scala
import cats.data.Kleisli
import cats.effect.Async
import skunk._
import skunk.codec.all._
import skunk.implicits._

class SkunkPersonRepository[F[_]: Async] extends PersonRepository[[A] =>> Kleisli[F, Session[F], A]] {
  private val personCodec: Codec[Person] =
    (uuid ~ text ~ int4).imap { case id ~ name ~ age => Person(id, name, age) }(p =>
      p.id ~ p.name ~ p.age)

  def create: Kleisli[F, Session[F], Unit] =
    Kleisli(_.execute(sql"CREATE TABLE IF NOT EXISTS persons (id uuid PRIMARY KEY, name text NOT NULL, age int4 NOT NULL)".command).void)

  def insertMany(persons: List[Person]): Kleisli[F, Session[F], Long] = Kleisli { session =>
    session.prepareR(sql"INSERT INTO persons (id, name, age) VALUES ($personCodec)".command)
      .use(pc => persons.traverse(pc.execute).map(_.length.toLong))
  }

  def deleteWhenOlderThen(age: Long): Kleisli[F, Session[F], Long] = Kleisli { session =>
    session.prepareR(sql"DELETE FROM persons WHERE age > ${int8}".command)
      .use(_.execute(age).map { case Completion.Delete(n) => n.toLong; case _ => 0L })
  }

  def listAll(): Kleisli[F, Session[F], List[Person]] =
    Kleisli(_.execute(sql"SELECT id, name, age FROM persons".query(personCodec)))
}
```

### 4. Mirror-test them

All three test framework integrations follow the same structure:

- Assign three abstract types: `BootstrapContext` (e.g. a running container), `MirraState` (the in-memory universe), and `TransactionEffect` (the real DB effect).
- Implement `bootstrapSystemUnderTest` to wire together both interpreters and a rollback transactor.
- Call `assertMirroring(context) { x => … }` in each test. `x` is your algebra running against both interpreters simultaneously; no manual assertion of specific values needed.

Each property iteration runs inside a transaction that is always rolled back, so the database stays clean without restarting the container.

#### munit + cats-effect

```scala
import cats.effect.IO
import cats.implicits.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}

class DoobiePersonRepositorySpec
    extends MirraSuite[IO, PersonRepository]
    with TestContainerForAll {

  given Arbitrary[Person] = Arbitrary {
    for {
      id   <- Gen.uuid
      name <- Gen.stringOfN(50, Gen.alphaChar)
      age  <- Gen.posNum[Int]
    } yield Person(id, name, age)
  }

  override val containerDef = PostgreSQLContainer.Def(
    dockerImageName = DockerImageName.parse("postgres:15.1"),
    databaseName    = "testcontainer-scala",
    username        = "scala",
    password        = "scala"
  )

  override type BootstrapContext     = Containers
  override type MirraState           = Universe
  override type TransactionEffect[A] = ConnectionIO[A]

  override def bootstrapSystemUnderTest(c: Containers): Resource[IO, SystemUnderTest] =
    Resource.pure(new SystemUnderTest(
      Universe.zero,
      DoobiePersonRepository,
      MirraPersonRepository,
      DoobieSupport.rollbackTrans[IO]("org.postgresql.Driver", c.jdbcUrl, c.username, c.password)
    ))

  test("should insert and read") {
    PropF.forAllF { (persons: List[Person]) =>
      withContainers { container =>
        assertMirroring(container) { x =>
          for {
            _ <- x.create
            _ <- x.insertMany(persons)
            r <- x.listAll()
          } yield r
        }
      }
    }
  }

  test("should delete people older than") {
    PropF.forAllF { (persons: List[Person], age: Int) =>
      withContainers { container =>
        assertMirroring(container) { x =>
          for {
            _ <- x.create
            _ <- x.insertMany(persons)
            _ <- x.deleteWhenOlderThen(age)
            r <- x.listAll()
          } yield r
        }
      }
    }
  }
}
```

#### ZIO Test

The ZIO Test integration fixes the effect type to `Task` (`ZIO[Any, Throwable, *]`). Bridging to Cats Effect (so that Doobie's `Transactor` works with `Task`) is done via `zio-interop-cats`. The container is managed with `ZLayer.scoped` + `provideShared` so it starts once per suite.

```scala
import cats.implicits.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import doobie.*
import org.testcontainers.utility.DockerImageName
import zio.*
import zio.interop.catz.*
import zio.test.*

object ZioDoobiePersonRepositorySpec extends MirraZIOSuite[PersonRepository] {

  override type BootstrapContext     = PostgreSQLContainer
  override type MirraState           = Universe
  override type TransactionEffect[A] = ConnectionIO[A]

  private val genPerson: Gen[Any, Person] =
    for {
      id   <- Gen.uuid
      name <- Gen.stringBounded(1, 50)(Gen.alphaNumericChar)
      age  <- Gen.int(1, 120)
    } yield Person(id, name, age)

  private val containerLayer: ZLayer[Any, Throwable, PostgreSQLContainer] =
    ZLayer.scoped {
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val c = PostgreSQLContainer(
            dockerImageNameOverride = DockerImageName.parse("postgres:15.1"),
            databaseName            = "testcontainer-scala",
            username                = "scala",
            password                = "scala"
          )
          c.start(); c
        }
      )(c => ZIO.attemptBlocking(c.stop()).orDie)
    }

  override def bootstrapSystemUnderTest(c: PostgreSQLContainer): ZIO[Scope, Throwable, SystemUnderTest] =
    ZIO.attempt(new SystemUnderTest(
      Universe.zero,
      DoobiePersonRepository,
      MirraPersonRepository,
      DoobieSupport.rollbackTrans[Task]("org.postgresql.Driver", c.jdbcUrl, c.username, c.password)
    ))

  def spec =
    suite("ZioDoobiePersonRepositorySpec")(

      test("should insert and read") {
        ZIO.serviceWithZIO[PostgreSQLContainer] { container =>
          check(Gen.listOf(genPerson)) { persons =>
            assertMirroring(container) { x =>
              for {
                _ <- x.create
                _ <- x.insertMany(persons)
                r <- x.listAll()
              } yield r
            }
          }
        }
      },

      test("should delete people older than") {
        ZIO.serviceWithZIO[PostgreSQLContainer] { container =>
          check(Gen.listOf(genPerson).zip(Gen.int(0, 200))) { case (persons, age) =>
            assertMirroring(container) { x =>
              for {
                _ <- x.create
                _ <- x.insertMany(persons)
                _ <- x.deleteWhenOlderThen(age)
                r <- x.listAll()
              } yield r
            }
          }
        }
      }

    ).provideShared(containerLayer)
}
```

Notice there is no assertion logic about _what_ the result should be. No filtering, no manual comparison. Just: "do both implementations agree?" The framework generates the inputs, runs both interpreters, and `assertMirroring` diffs the outputs.

## Key concepts

**`Mirra[S, A]`** — A `State`-like monad with built-in helpers for modeling CRUD operations (`insertMany`, `delete`, `all`, `unit`, etc.). Uses Monocle lenses to target collections within your state type `S`. This is where your expected behavior lives — in one place, as a real implementation, not scattered across test assertions.

**`SystemUnderTest`** — Wires together a real implementation and a model using `SemigroupalK` to run both through the same algebra simultaneously. Accepts a natural transformation `TransactionEffect ~> F` (or `~> Task` for ZIO) that interprets real database actions.

**`assertMirroring`** — Executes the program against both interpreters, diffs the results, and fails the test if they diverge.

**`MirraSuite[F[_], Alg[_[_]]]`** — A munit trait providing `assertMirroring`. Extends `CatsEffectSuite` and `ScalaCheckEffectSuite`; mix it into your test suite and implement `bootstrapSystemUnderTest`.

**`MirraZIOSuite[Alg[_[_]]]`** — A ZIO Test equivalent of `MirraSuite`. Extends `ZIOSpecDefault`; the effect type is fixed to `Task`. Property inputs come from ZIO Test's built-in `Gen`; resource management uses `ZLayer` + `provideShared`.

**`FunctorK` / `SemigroupalK`** — Type classes from [cats-tagless](https://github.com/typelevel/cats-tagless) that allow transforming the effect type of an algebra. These are what make it possible to run a single program against two different interpreters. Derived automatically with `Derive.functorK` / `Derive.semigroupalK`.

## Modules

| Module | What it provides |
|---|---|
| `core` | `Mirra[S, A]`, `MirraSyntax` |
| `munit` | `MirraSuite[F, Alg]` — munit + cats-effect + ScalaCheck integration |
| `zio-test` | `MirraZIOSuite[Alg]` — ZIO Test integration (effect fixed to `Task`) |
| `doobie` | `DoobieSupport.rollbackTrans` — `ConnectionIO ~> F` with always-rollback |
| `skunk` | `SkunkSupport.rollbackTrans` — `Kleisli[F, Session[F], *] ~> F` with always-rollback |

## Dependencies

Built with Scala 3.

**Core:** [cats-tagless](https://github.com/typelevel/cats-tagless) (FunctorK / SemigroupalK derivation), [Monocle](https://github.com/optics-dev/Monocle) (lenses for state manipulation).

**munit module:** [munit](https://scalameta.org/munit/), [munit-cats-effect](https://github.com/typelevel/munit-cats-effect), [scalacheck-effect-munit](https://github.com/typelevel/scalacheck-effect).

**zio-test module:** [ZIO](https://zio.dev/) (`zio`, `zio-test`, `zio-test-sbt`).

**doobie module:** [Doobie](https://github.com/tpolecat/doobie).

**skunk module:** [Skunk](https://github.com/tpolecat/skunk).

**Example module:** Doobie + Skunk + ZIO, [zio-interop-cats](https://github.com/zio/interop-cats) (bridges `Task` to Cats Effect `Async`), [Testcontainers](https://www.testcontainers.org/) (PostgreSQL).

## Inspiration

This library implements the [test oracle](https://fsharpforfunandprofit.com/posts/property-based-testing-2/#test-oracle) pattern described by Scott Wlaschin, applied to tagless final algebras: maintain a simplified model alongside the system under test, apply the same operations to both, and compare final states.

## License

This project is archived. Feel free to use it as a reference or fork it for your own needs.
