# Mirra

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

Rather than listing properties upfront, I'd describe it as: the model defines the expected semantics; anything the real implementation does differently is a bug. The specific property names (locality, idempotency, etc.) are just post-hoc labels for classes of divergence you observe. The strength of the approach is you don't need to enumerate them — but that also means gaps in your model or generator leave blind spots.

## Key concepts

**`Mirra[S, A]`** — A `State`-like monad with built-in helpers for modeling CRUD operations (`insertMany`, `delete`, `all`, `unit`, etc.). Uses Monocle lenses to target collections within your state type `S`. This is where your expected behavior lives — in one place, as a real implementation, not scattered across test assertions.

**`SystemUnderTest`** — Wires together a real implementation and a model using `SemigroupalK` to run both through the same algebra simultaneously. Accepts a natural transformation `TransactionEffect ~> F` (or `~> Task` for ZIO) that interprets real database actions.

**`assertMirroring`** — Executes the program against both interpreters, diffs the results, and fails the test if they diverge.

**`MirraMunitSuite[F[_], Alg[_[_]]]`** — A munit trait providing `assertMirroring`. Extends `CatsEffectSuite` and `ScalaCheckEffectSuite`; mix it into your test suite and implement `bootstrapSystemUnderTest`.

**`MirraZIOSuite[Alg[_[_]]]`** — A ZIO Test equivalent of `MirraMunitSuite`. Extends `ZIOSpecDefault`; the effect type is fixed to `Task`. Property inputs come from ZIO Test's built-in `Gen`; resource management uses `ZLayer` + `provideShared`.

**`FunctorK` / `SemigroupalK`** — Type classes from [cats-tagless](https://typelevel.org/cats-tagless/) that lift familiar abstractions to the level of type constructors of kind `(* -> *) -> *` — i.e., algebras whose effect type is a parameter. `FunctorK` lets you map the effect type of an algebra (e.g. turn `Alg[IO]` into `Alg[Option]`). `SemigroupalK` lets you combine two interpretations into one that runs both simultaneously — which is exactly how `SystemUnderTest` wires the real implementation together with the in-memory model. Both are derived automatically with `Derive.functorK` / `Derive.semigroupalK`. See the [cats-tagless documentation](https://typelevel.org/cats-tagless/) to learn more.

**State monad** — `Mirra[S, A]` is built on top of the `State[S, A]` monad: a pure description of a computation that reads from and writes to a value of type `S`, returning a result of type `A`. Sequencing `State` actions threads the state through automatically without any mutable variables. If you are new to the State monad, the [Cats documentation](https://typelevel.org/cats/datatypes/state.html) has a gentle introduction.

**Lenses** — Mirra uses [Monocle](https://www.optics.dev/Monocle/) lenses to focus on a specific collection within your state type `S`. A lens is a composable, type-safe getter/setter pair: given a large state object, a lens lets you zoom in on one field, apply a transformation, and return the updated whole — without touching anything else. See the [Monocle documentation](https://www.optics.dev/Monocle/) for a full introduction to optics.

## Modules

| Module | What it provides |
|---|---|
| `core` | `Mirra[S, A]`, `MirraSyntax` |
| `munit` | `MirraMunitSuite[F, Alg]` — munit + cats-effect + ScalaCheck integration |
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
