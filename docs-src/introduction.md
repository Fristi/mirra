# Mirra

**Mirror-test your tagless final algebras in Scala.**

Mirra makes it easy to build in-memory implementations of tagless final repository algebras. Once you have one, several things become possible: mirror-testing against a real database, fast service-layer tests, and temporary in-memory stand-ins during development.

## What Mirra gives you

### 1. Easy in-memory implementations

Mirra provides `Mirra[S, *]`, a specialized `State` monad with built-in CRUD combinators (`insertMany`, `delete`, `all`, etc.) that operate over a simple in-memory state `S` using Monocle lenses. These combinators cover the common patterns, so you spend almost no time writing boilerplate — you describe the shape of your state and lens into it, and Mirra handles the list semantics.

### 2. Mirror-testing against a real database

#### Why property-based testing for databases at all?

Unit tests with hand-picked examples are good at verifying known cases. What they miss are the cases you didn't think to write. Property-based testing generates hundreds of random inputs, which tends to surface two classes of bug that example tests routinely miss:

**Data preservation.** Your Scala model has a `String` field. Your schema has `VARCHAR(50)`. For most test data that works fine — but a generator that produces strings up to length 200 will eventually find the truncation. The same applies to numeric precision (a `Double` that doesn't round-trip through a `NUMERIC(10,2)` column), timezone handling, null semantics, and any other mismatch between your domain types and the database's type system. These bugs only show up under data you wouldn't normally reach for when writing an example test.

**Locality of queries and mutations.** A `delete` that should only affect matching rows shouldn't touch anything else. An `update` on one record shouldn't bleed into another. Property-based tests generate multiple records at once, including records with similar but distinct values, which makes it much more likely to catch a query that's too broad — a missing `WHERE` clause, an off-by-one in a range filter, or a join condition that accidentally matches more rows than intended.

#### The duplicated-expectations trap

Property-testing a repository directly has a subtle trap: you end up re-implementing its logic in your assertions. Suppose you're testing a `deleteWhenOlderThen` method. Your property test generates random persons and a random age threshold, calls the repository, and then you need to assert the right people were deleted:

```scala
prop { (persons: List[Person], age: Int) =>
  repo.insertMany(persons)
  repo.deleteWhenOlderThen(age)
  val remaining = repo.listAll()

  // Re-implementing the repository's filter logic right here in the test
  remaining must_== persons.filter(_.age <= age)
}
```

Your assertion duplicates the exact filtering logic the repository is supposed to implement. If you get the assertion wrong (off-by-one, wrong comparison operator, edge case), the test is worthless — and you won't know it.

The fix is to replace ad-hoc assertions with a proper in-memory implementation of the same algebra. This is the [test oracle](https://fsharpforfunandprofit.com/posts/property-based-testing-2/#test-oracle) pattern: instead of asserting _what_ the result should be, you assert that two implementations _agree_.

Wire both into a `SystemUnderTest`, which uses `SemigroupalK` (from cats-tagless) to run the same program against both interpreters simultaneously:

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

If they diverge, either the real implementation has a bug, or the model is wrong — both worth finding.

### 3. Fast, accurate service-layer tests

Once the in-memory model is validated against the real implementation, you can use it as a drop-in replacement in service-layer unit tests. No database, no containers, no network — just fast, deterministic tests that you _know_ are behaviorally accurate, because the model has been validated against the real thing.

This is much better than mocks: a mock returns whatever you tell it to, even outputs the real implementation would never produce for a given input. A validated in-memory model can't lie that way.

### 4. Temporary in-memory stand-ins

During early development, before a real backend exists, a Mirra implementation can serve as a temporary stand-in. Because it's based on `scala.List` semantics, it is not appropriate for large collections — time and space complexity will not match a proper database — but for getting business logic off the ground before infrastructure is ready, it works well.

## How it works

1. **Define** a tagless final algebra for your repository.
2. **Implement** it for real — against a database, HTTP API, etc.
3. **Model** it with `Mirra[S, *]`, using the built-in CRUD combinators and Monocle lenses over your state type `S`.
4. **Wire** both into a `SystemUnderTest`, which uses `SemigroupalK` (from cats-tagless) to run the same program against both interpreters simultaneously.
5. **Assert mirroring** — for any randomly generated input, both must produce the same result.

### What properties fall out of this?

Rather than listing properties upfront, I'd describe it as: the model defines the expected semantics; anything the real implementation does differently is a bug. The specific property names (locality, idempotency, etc.) are just post-hoc labels for classes of divergence you observe. The strength of the approach is you don't need to enumerate them — but that also means gaps in your model or generator leave blind spots.

## Inspiration

This library implements the [test oracle](https://fsharpforfunandprofit.com/posts/property-based-testing-2/#test-oracle) pattern described by Scott Wlaschin, applied to tagless final algebras: maintain a simplified model alongside the system under test, apply the same operations to both, and compare final states.
