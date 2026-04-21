# Mirra

**Mirror-test your tagless final algebras in Scala.**

Mirra verifies that a real repository implementation behaves the same way as a simple in-memory model — using property-based testing to catch the bugs you'd never think to write a case for.


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

## Inspiration

This library implements the [test oracle](https://fsharpforfunandprofit.com/posts/property-based-testing-2/#test-oracle) pattern described by Scott Wlaschin, applied to tagless final algebras: maintain a simplified model alongside the system under test, apply the same operations to both, and compare final states.
