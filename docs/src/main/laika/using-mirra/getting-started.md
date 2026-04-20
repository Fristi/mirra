# Getting Started

The full example shown here is also available in the [`example`](https://github.com/Fristi/mirra/tree/master/example) module.



## 1. Define the algebra

Start with a tagless final algebra for your repository. The algebra needs `FunctorK` and `SemigroupalK` instances so that `SystemUnderTest` can run a single program through both interpreters simultaneously. cats-tagless can derive them automatically.

```scala mdoc
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
  given FunctorK[PersonRepository]    = Derive.functorK
  given SemigroupalK[PersonRepository] = Derive.semigroupalK
}
```

## 2. Write the in-memory model

Define a "universe" — a case class holding the state — and implement the algebra using Mirra's CRUD helpers with Monocle `Focus` lenses. This model is the single source of truth for expected behavior. It's so simple (append to a list, filter a list, return a list) that it's hard to get wrong.

```scala mdoc
import mirra.Mirra
import monocle.Focus

final case class Universe(persons: List[Person])

object Universe {
  def zero: Universe = Universe(Nil)
  
  val personsL = Focus[Universe](_.persons)
}

object MirraPersonRepository extends PersonRepository[[A] =>> Mirra[Universe, A]] {
  def create: Mirra[Universe, Unit] =
    Mirra.unit

  def insertMany(persons: List[Person]): Mirra[Universe, Long] =
    Mirra.insertMany(Universe.personsL)(persons)

  def deleteWhenOlderThen(age: Long): Mirra[Universe, Long] =
    Mirra.delete(Universe.personsL)(_.age > age)

  def listAll(): Mirra[Universe, List[Person]] =
    Mirra.all(Universe.personsL)
}
```

## Next steps

With the algebra and model in place, wire in a real database backend and a test framework integration:

- [Combinator reference](combinators.md) — every `Mirra` operator and `MirraSyntax` extension with examples
- [Composing repositories](composing-repositories.md) — test multiple repositories together in a single `SystemUnderTest`
- [In-memory interpreters](../in-memory-interpreters/cats-effect.md) — use the model as a drop-in algebra in cats-effect or ZIO service tests
- [Doobie](../data-layer-testing/doobie.md) — `ConnectionIO`-based backend
- [Skunk](../data-layer-testing/skunk.md) — `Kleisli[F, Session[F], *]`-based backend
- [munit + cats-effect](../data-layer-testing/munit.md) — property-test with `MirraSuite`
- [ZIO Test](../data-layer-testing/zio-test.md) — property-test with `MirraZIOSuite`
