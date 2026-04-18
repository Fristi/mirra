# mirra

**Mirror-test your tagless final algebras in Scala.**

Mirra verifies that a real repository implementation behaves the same way as a simple in-memory model — using property-based testing to catch the bugs you'd never think to write a case for.

> **Status:** This project is not active and was a proof-of-concept. It may still be useful as a reference or starting point.

## The problem: duplicated expectations

When you property-test a repository directly, you end up re-implementing its logic in your assertions. Suppose you're testing a `deleteWhenOlderThan` method. Your property test generates random persons and a random age threshold, calls the repository, and then you need to assert the right people were deleted. To do that, you filter the generated input list yourself:

```scala
prop { (persons: List[Person], age: Int) =>
  repo.insertMany(persons)
  repo.deleteWhenOlderThan(age)
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
4. **Wire** both into a `Harness`, which uses `FunctorK` / `SemigroupalK` (from cats-tagless) to run the same program against both interpreters.
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
final case class Person(id: UUID, name: String, age: Int)

trait PersonRepository[F[_]] {
  def create: F[Unit]
  def insertMany(persons: List[Person]): F[Long]
  def deleteWhenOlderThan(age: Long): F[Long]
  def listAll(): F[List[Person]]
}

object PersonRepository {
  // These let the Harness run both interpreters through one algebra
  implicit val functorK: FunctorK[PersonRepository] = Derive.functorK
  implicit val semigroupalK: SemigroupalK[PersonRepository] = Derive.semigroupalK
}
```

### 2. Write the in-memory model

Define a "universe" — a case class holding your state — and implement the algebra using Mirra's CRUD helpers with Monocle lenses.

```scala
@Lenses
final case class Universe(persons: List[Person])

object Universe {
  def zero: Universe = Universe(Nil)
}

object InMemoryPersonRepository extends PersonRepository[Mirra[Universe, *]] {
  def insertMany(persons: List[Person]): Mirra[Universe, Long] =
    Mirra.insertMany(Universe.persons)(persons)

  def deleteWhenOlderThan(age: Long): Mirra[Universe, Long] =
    Mirra.delete(Universe.persons)(_.age > age)

  def listAll(): Mirra[Universe, List[Person]] =
    Mirra.all(Universe.persons)

  def create: Mirra[Universe, Unit] =
    Mirra.unit
}
```

This is your model — the single source of truth for expected behavior. It's so simple (append to a list, filter a list, return a list) that it's hard to get wrong.

### 3. Write the real implementation

```scala
object DoobiePersonRepository extends PersonRepository[ConnectionIO] {

  object queries {
    def deleteWhenOlderThan(age: Long): Update0 =
      fr"delete from persons where age > $age".update

    def create: Update0 =
      fr"""create table if not exists persons (
        |  id uuid primary key,
        |  name text not null,
        |  age numeric not null
        |)""".stripMargin.update

    def listAll: Query0[Person] =
      fr"select id, name, age from persons".query[Person]
  }

  def insertMany(persons: List[Person]): ConnectionIO[Long] =
    Update[Person]("insert into persons (id, name, age) values (?, ?, ?)")
      .updateMany(persons).map(_.toLong)

  def deleteWhenOlderThan(age: Long): ConnectionIO[Long] =
    queries.deleteWhenOlderThan(age).run.map(_.toLong)

  def listAll(): ConnectionIO[List[Person]] =
    queries.listAll.to[List]

  def create: ConnectionIO[Unit] =
    queries.create.run.void
}
```

### 4. Mirror-test them

```scala
class PersonRepoSpec extends Specification with DoobieSpec {

  def harness: Harness[PersonRepository, IO, ConnectionIO, Universe] =
    new Harness(Universe.zero, DoobiePersonRepository, InMemoryPersonRepository, xa.trans)

  "PersonRepository" should {

    "not lose data on insert → read" in {
      prop { persons: List[Person] =>
        assertMirroring {
          harness.model.eval { x =>
            x.create *>
              x.insertMany(persons) *>
              x.listAll()
          }
        }
      }
    }

    "delete only people older than the threshold" in {
      prop { (persons: List[Person], age: Int) =>
        assertMirroring {
          harness.model.eval { x =>
            x.create *>
              x.insertMany(persons) *>
              x.deleteWhenOlderThan(age) *>
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

**`Harness[Alg, F, G, S]`** — Wires together a real implementation (`Alg[G]`) and a model (`Alg[Mirra[S, *]]`), using `FunctorK` / `SemigroupalK` to run both through the same algebra and compare results.

**`assertMirroring`** — Executes the program against both interpreters, diffs the results, and fails the test if they diverge.

**`FunctorK` / `SemigroupalK`** — Type classes from [cats-tagless](https://github.com/typelevel/cats-tagless) that allow transforming the effect type of an algebra. These are what make it possible to run a single program against two different interpreters. Derived automatically with `Derive.functorK` / `Derive.semigroupalK`.

## Dependencies

Built with Scala 2.13.

**Core:** [cats-tagless](https://github.com/typelevel/cats-tagless) (FunctorK / SemigroupalK derivation), [Monocle](https://github.com/optics-dev/Monocle) (lenses for state manipulation).

**Example module (test):** [Doobie](https://github.com/tpolecat/doobie), [Testcontainers](https://www.testcontainers.org/), [specs2](https://etorreborre.github.io/specs2/) + [ScalaCheck](https://scalacheck.org/), [magnolify-scalacheck](https://github.com/spotify/magnolify), [diffx](https://github.com/softwaremill/diffx).

## Inspiration

This library implements the [test oracle](https://fsharpforfunandprofit.com/posts/property-based-testing-2/#test-oracle) pattern described by Scott Wlaschin, applied to tagless final algebras: maintain a simplified model alongside the system under test, apply the same operations to both, and compare final states.

## License

This project is archived. Feel free to use it as a reference or fork it for your own needs.