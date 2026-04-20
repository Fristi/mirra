# cats-effect

The `cats-effect` module provides `MirraCatsEffect.make` — a factory that lifts an `Alg[[A] =>> Mirra[D, A]]` into `F[Alg[F]]` by backing it with a `cats.effect.Ref[F, D]`.

```scala
libraryDependencies += "io.github.fristi" %% "mirra-cats-effect" % "<version>"
```

## How it works

`MirraCatsEffect.make` allocates a `Ref[F, D]` seeded with `initialState`, then uses `FunctorK[Alg].mapK` to rewrite every algebra method: each `Mirra[D, A]` (a `State[D, A]` under the hood) is run atomically against the ref via `Ref.modify`.

```
Alg[[A] =>> Mirra[D, A]]  +  D  →  F[Alg[F]]
```

The returned `Alg[F]` is a live, stateful interpreter. State mutations from one call are visible to subsequent calls, exactly as they would be in a real database — but entirely in-memory.

## Testing services that abstract over `F[_]`

The typical pattern in a cats-effect codebase is to keep business logic polymorphic:

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

import cats.MonadThrow
import cats.implicits._

class PersonService[F[_]: MonadThrow](repo: PersonRepository[F]) {
  def registerAdult(person: Person): F[Unit] =
    if (person.age < 18)
      MonadThrow[F].raiseError(new IllegalArgumentException("must be 18 or older"))
    else
      repo.insertMany(List(person)).void

  def listAll(): F[List[Person]] =
    repo.listAll()
}
```

In tests, instantiate the service by wiring it to an in-memory `PersonRepository[IO]`:

```scala mdoc:compile-only
import cats.effect.IO
import cats.implicits.*
import mirra.MirraCatsEffect
import munit.CatsEffectSuite
import java.util.UUID

class PersonServiceSpec extends CatsEffectSuite {

  // Fresh state for each test — call make inside the test body.
  def mkRepo: IO[PersonRepository[IO]] =
    MirraCatsEffect.make[IO, PersonRepository, Universe](MirraPersonRepository, Universe.zero)

  test("registerAdult inserts the person") {
    for {
      repo    <- mkRepo
      service  = new PersonService[IO](repo)
      person   = Person(UUID.randomUUID(), "Alice", 30)
      _       <- service.registerAdult(person)
      result  <- repo.listAll()
    } yield assertEquals(result, List(person))
  }

  test("registerAdult rejects minors") {
    for {
      repo    <- mkRepo
      service  = new PersonService[IO](repo)
      minor    = Person(UUID.randomUUID(), "Bob", 16)
      result  <- service.registerAdult(minor).attempt
    } yield assert(result.isLeft)
  }

  test("listAll returns all registered persons") {
    for {
      repo    <- mkRepo
      service  = new PersonService[IO](repo)
      people   = List(
                   Person(UUID.randomUUID(), "Alice", 30),
                   Person(UUID.randomUUID(), "Carol", 25),
                 )
      _       <- repo.insertMany(people)
      result  <- service.listAll()
    } yield assertEquals(result.map(_.name), List("Alice", "Carol"))
  }
}
```

Because `make` returns `F[Alg[F]]`, calling it inside each test body gives every test its own isolated `Ref` — no shared state, no `beforeEach` cleanup.
