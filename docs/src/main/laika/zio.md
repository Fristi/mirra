# ZIO

The `zio` module provides `MirraZIO.layer` — a factory that lifts an `Alg[[A] =>> Mirra[D, A]]` into a `ULayer[Alg[Task]]` by backing it with a `zio.Ref[D]`.

```scala
libraryDependencies += "io.github.fristi" %% "mirra-zio" % "<version>"
```

## How it works

`MirraZIO.layer` wraps `Ref.make(initialState)` in a `ZLayer`, then uses `FunctorK[Alg].mapK` to rewrite every algebra method: each `Mirra[D, A]` (a `State[D, A]` under the hood) is run atomically against the ref via `Ref.modify`.

```
Alg[[A] =>> Mirra[D, A]]  +  D  →  ULayer[Alg[Task]]
```

The provided `Alg[Task]` is a live, stateful interpreter. State mutations from one call are visible to subsequent calls, exactly as they would be in a real database — but entirely in-memory.

## Testing services that take `Repo[Task]`

The typical pattern in a ZIO codebase is for services to depend on concrete `Task`-typed repositories injected via `ZLayer`:

```scala mdoc:invisible
import cats.tagless.{Derive, FunctorK, SemigroupalK}
import java.util.UUID
import mirra.Mirra
import monocle.Focus
import cats.implicits._

final case class Person(id: UUID, name: String, age: Int)

trait PersonRepository[F[_]] {
  def create: F[Unit]
  def insertMany(persons: List[Person]): F[Long]
  def deleteWhenOlderThen(age: Long): F[Long]
  def listAll(): F[List[Person]]
}

object PersonRepository {
  given FunctorK[PersonRepository]     = Derive.functorK
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
```

```scala mdoc:compile-only
import zio.*

// Service receives a concrete PersonRepository[Task] — no F[_] abstraction needed in ZIO.
class PersonService(repo: PersonRepository[Task]) {

  def registerAdult(person: Person): Task[Unit] =
    ZIO.fail(new IllegalArgumentException("must be 18 or older")).when(person.age < 18) *>
      repo.insertMany(List(person)).unit

  def listAdults(): Task[List[Person]] =
    repo.listAll().map(_.filter(_.age >= 18))
}
```

Wire the service against an in-memory `PersonRepository[Task]` using `MirraZIO.layer` as a `ZLayer`:

```scala mdoc:compile-only
import mirra.MirraZIO
import zio.*
import zio.test.*
import java.util.UUID

object PersonServiceSpec extends ZIOSpecDefault {

  // Fresh Ref per test — provide the layer inside each test's ZIO.scoped block,
  // or use provideLayer at the test level to keep tests isolated.
  val repoLayer: ULayer[PersonRepository[Task]] =
    MirraZIO.layer(MirraPersonRepository, Universe.zero)

  def spec = suite("PersonService")(

    test("registerAdult inserts the person") {
      ZIO.serviceWithZIO[PersonRepository[Task]] { repo =>
        val service = PersonService(repo)
        val person  = Person(UUID.randomUUID(), "Alice", 30)
        for {
          _      <- service.registerAdult(person)
          result <- repo.listAll()
        } yield assertTrue(result == List(person))
      }
    }.provide(repoLayer),

    test("registerAdult rejects minors") {
      ZIO.serviceWithZIO[PersonRepository[Task]] { repo =>
        val service = PersonService(repo)
        val minor   = Person(UUID.randomUUID(), "Bob", 16)
        service.registerAdult(minor).flip.map { err =>
          assertTrue(err.getMessage == "must be 18 or older")
        }
      }
    }.provide(repoLayer),

    test("listAdults filters correctly") {
      ZIO.serviceWithZIO[PersonRepository[Task]] { repo =>
        val service = PersonService(repo)
        val people  = List(
                        Person(UUID.randomUUID(), "Alice", 30),
                        Person(UUID.randomUUID(), "Bob",   16),
                      )
        for {
          _      <- repo.insertMany(people)
          result <- service.listAdults()
        } yield assertTrue(result.map(_.name) == List("Alice"))
      }
    }.provide(repoLayer),

  )
}
```

Each `.provide(repoLayer)` call rebuilds the layer from scratch — including a fresh `Ref` — so tests are fully isolated without any shared state.
