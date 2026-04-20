# ZIO Test

The `zio-test` module provides `MirraZIOSuite[Alg[_[_]]]` — a trait that extends `ZIOSpecDefault` with Mirra's mirror-testing machinery. The effect type is fixed to `Task` (`ZIO[Any, Throwable, *]`).

```scala
libraryDependencies += "io.github.fristi" %% "mirra-zio-test" % "<version>"
```

Bridging to Cats Effect (so that Doobie's `Transactor` works with `Task`) is done via `zio-interop-cats`. Add it alongside the backend module:

```scala
libraryDependencies += "dev.zio" %% "zio-interop-cats" % "<version>"
```

## What is a SystemUnderTest?

A `SystemUnderTest` holds two interpreters of your algebra running side-by-side:

- The **real** interpreter — your actual database-backed implementation (e.g. Doobie or Skunk).
- The **model** interpreter — a pure in-memory `Mirra` implementation that acts as a reference oracle.

Both interpreters are fused into a single `Alg[PairedEffect]` using `SemigroupalK.productK`. When you call `assertMirroring`, the same program is threaded through both at once, returning `(realResult, modelResult)`, and the framework asserts they are equal using ZIO Test's `assertTrue` macro, which produces a readable diff on failure.

Constructing a `SystemUnderTest` requires four arguments:

| Argument | Type | Description |
|---|---|---|
| `initState` | `MirraState` | Starting in-memory state (e.g. `Universe.zero`) |
| `db` | `Alg[TransactionEffect]` | The real database interpreter |
| `model` | `Alg[MirraEffect]` | The Mirra model interpreter |
| `tx` | `TransactionEffect ~> Task` | Runs (and always rolls back) a DB action as a `Task` |

## How to implement

### Step 1 — Supply three abstract types

| Type | Purpose | Example |
|---|---|---|
| `BootstrapContext` | Infrastructure value produced by your ZLayer bootstrap | `PostgreSQLContainer` |
| `MirraState` | In-memory domain state threaded through the model interpreter | `Universe` |
| `TransactionEffect[_]` | Real database effect monad | `ConnectionIO` (Doobie) |

### Step 2 — Implement `bootstrapSystemUnderTest`

Return a `ZIO[Scope, Throwable, SystemUnderTest]` that acquires your database connection / transactor and constructs a `new SystemUnderTest(initState, db, model, tx)`. Use `ZIO.acquireRelease` or `ZLayer.scoped` for resources that need cleanup. Use `DoobieSupport.rollbackTrans` (lifted via `zio-interop-cats`) or `SkunkSupport.rollbackTrans` for the `tx` argument.

### Step 3 — Write property tests using ZIO Test

Define `spec` using `suite(...)` / `test(...)`. Inside each test, call `assertMirroring(context) { x => … }` where `x` is your algebra running both interpreters simultaneously — write your program against it exactly as you would in production code. Share expensive resources (e.g. containers) across tests with `.provideShared(layer)` so they start **once per suite**.

## Full example

```scala mdoc:invisible
import cats.tagless.{Derive, FunctorK, SemigroupalK}
import java.util.UUID
import mirra.Mirra
import monocle.Focus
import cats.implicits._
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

final case class Person(id: UUID, name: String, age: Int)

trait PersonRepository[F[_]] {
  def create: F[Unit]
  def insertMany(persons: List[Person]): F[Long]
  def deleteWhenOlderThen(age: Long): F[Long]
  def listAll(): F[List[Person]]
}

object PersonRepository {
  given FunctorK[PersonRepository]    = Derive.functorK
  given SemigroupalK[PersonRepository] = Derive.semigroupalK
}

final case class Universe(persons: List[Person])
object Universe { def zero: Universe = Universe(Nil) }

object MirraPersonRepository extends PersonRepository[[A] =>> Mirra[Universe, A]] {
  def create: Mirra[Universe, Unit] = Mirra.unit
  def insertMany(persons: List[Person]): Mirra[Universe, Long] =
    Mirra.insertMany(Focus[Universe](_.persons))(persons)
  def deleteWhenOlderThen(age: Long): Mirra[Universe, Long] =
    Mirra.delete(Focus[Universe](_.persons))(_.age > age)
  def listAll(): Mirra[Universe, List[Person]] =
    Mirra.all(Focus[Universe](_.persons))
}

object DoobiePersonRepository extends PersonRepository[ConnectionIO] {
  def create: ConnectionIO[Unit] =
    fr"""create table if not exists persons (
      |  id uuid primary key, name varchar(50) not null, age numeric not null
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

```scala mdoc:compile-only
import cats.implicits.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import doobie.*
import mirra.{DoobieSupport, MirraZIOSuite}
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

  // Starts once per suite; stopped on teardown via acquireRelease.
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
      },

    ).provideShared(containerLayer)
}
```

Notice there is no assertion logic about _what_ the result should be. The `check` block generates the inputs, runs both interpreters, and `assertMirroring` diffs the outputs.
