# Using it in ZIO

`Mirra[S, A]` is a pure `State`-like computation. Because `Mirra[S, *]` is a `Monad`, you sequence operations with `for`-comprehensions and evaluate the entire program in one shot with `run(initialState)`. No database, no containers — ideal for fast service-layer unit tests.

## Dependency

```scala
libraryDependencies += "io.github.fristi" %% "mirra-core" % "<version>"
```

## Running in Task

Wrap `run` in `ZIO.succeed` to lift the result into a ZIO `Task`:

```scala mdoc:compile-only
import zio.{Task, ZIO}
import mirra.Mirra

val program: Mirra[Universe, List[Person]] = for {
  _ <- MirraPersonRepository.create
  _ <- MirraPersonRepository.insertMany(persons)
  r <- MirraPersonRepository.listAll()
} yield r

val result: Task[List[Person]] = ZIO.succeed(program.run(Universe.zero))
```

`Mirra[S, *]` threads state automatically through the `for`-comprehension. `run(Universe.zero)` evaluates the whole chain at once and returns the final result; the intermediate state is not observable from the outside.

## Service-layer tests

Wire `MirraPersonRepository` directly into a tagless final service. Because the service is parameterised on `F[_]`, it works with `[A] =>> Mirra[Universe, A]` as-is:

```scala mdoc:compile-only
import zio.{Task, ZIO}

class PersonService[F[_]: cats.Monad](repo: PersonRepository[F]) {
  def createAndList(persons: List[Person]): F[List[Person]] = for {
    _ <- repo.create
    _ <- repo.insertMany(persons)
    r <- repo.listAll()
  } yield r
}

val service = new PersonService(MirraPersonRepository)

val result: Task[List[Person]] =
  ZIO.succeed(service.createAndList(persons).run(Universe.zero))
```

Because the model has been validated against a real database via `MirraZIOSuite`, this service test is behaviorally accurate — not just a mock that returns whatever you tell it to.

> To verify model against a real database, see [Test suite — ZIO Test](../data-layer-testing/zio-test.md).
