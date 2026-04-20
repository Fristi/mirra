# Combinators Reference

`Mirra` provides two complementary layers:

- **State operations** (`Mirra` companion object) — modify in-memory state: insert, update, delete, upsert, etc.
- **Query syntax** (`MirraSyntax`) — transform and aggregate the *result* of a `Mirra[D, List[A]]` program: filter, sort, join, paginate.

All state operations take a Monocle `Lens` that targets the collection to operate on. Query syntax methods are extension methods on `Mirra[D, F[A]]` — mix `MirraSyntax` into your suite or import it:

```scala
class MySpec extends MirraMunitSuite[IO, MyAlg] with MirraSyntax { ... }
```

---

Some basics!

```scala mdoc
import cats.implicits.*
import mirra.*
import monocle.Focus

case class Person(id: Int, name: String, age: Int)
case class Order(id: Int, personId: Int, amount: Int)
case class Universe(persons: List[Person], orders: List[Order])

val personsL = Focus[Universe](_.persons)
val ordersL = Focus[Universe](_.orders)

object Universe:
  val zero: Universe = Universe(Nil, Nil)

def run[A](prog: Mirra[Universe, A], state: Universe = Universe.zero): A = prog.run(state)

val alice = Person(1, "Alice", 30)
val bob   = Person(2, "Bob",   25)
val carol = Person(3, "Carol", 35)

val three = Universe(List(alice, bob, carol), Nil)
```

## State operations

### `unit` and `succeed`

`unit` produces `Unit` without touching state — useful to model a no-op such as a schema-creation step that is meaningless in memory. `succeed` lifts any pure value.

```scala mdoc
run(Mirra.unit[Universe])
run(Mirra.succeed[Universe, String]("hello"))
```

### `all`

Returns every element of the targeted collection without modifying state.

```scala mdoc
run(Mirra.all(personsL), three)
```

### `insert` / `insertMany` / `insertMany_`

`insert` appends one element; `insertMany` appends a list. Both return the number of rows inserted as `Long`. The `_` suffix variant returns the inserted elements instead of the count.

```scala mdoc
run(Mirra.insert(personsL)(alice))
run(Mirra.insertMany(personsL)(List(alice, bob)))
run(Mirra.insertMany_(personsL)(List(alice, bob)))
```

State accumulates across steps in a `for`-comprehension:

```scala mdoc
run(
  for {
    _ <- Mirra.insert(personsL)(alice)
    _ <- Mirra.insert(personsL)(bob)
    r <- Mirra.all(personsL)
  } yield r
)
```

### `update` / `update_`

`update(lens)(filter, transform)` applies `transform` to every element satisfying `filter` and returns the count of affected rows. `update_` returns the **pre-transformation** elements instead.

```scala mdoc
run(
  for {
    n <- Mirra.update(personsL)(_.age > 28, _.copy(age = 99))
    r <- Mirra.all(personsL)
  } yield (n, r),
  three
)
```

### `delete` / `delete_`

`delete(lens)(filter)` removes every element satisfying `filter` and returns the count. `delete_` returns the removed elements.

```scala mdoc
run(
  for {
    removed   <- Mirra.delete_(personsL)(_.age < 28)
    remaining <- Mirra.all(personsL)
  } yield (removed, remaining),
  three
)
```

### `truncate`

Clears the entire collection and returns the number of elements that existed before.

```scala mdoc
run(Mirra.truncate(personsL), three)
```

### `upsert` / `upsertMany`

Insert-or-replace based on a conflict key. When an existing element shares the same key, the `update: A => A` function is applied to it; otherwise the item is appended. Returns the number of affected rows.

```scala mdoc
// Replace alice's name on conflict (same id)
val renamed = alice.copy(name = "Alicia")
run(
  for {
    _ <- Mirra.upsert(personsL)(_.id, _ => renamed, alice)
    r <- Mirra.all(personsL)
  } yield r,
  three
)
```

`upsertMany` does the same for a list; `upsertMany_` returns the affected elements.

### `upsertWith` / `upsertManyWith`

Like `upsert` but uses a binary merge function `(existing, incoming) => A`, so you can choose which fields from the incoming record to apply — mirroring Postgres `ON CONFLICT DO UPDATE SET col = EXCLUDED.col`. Returns the number of affected rows.

```scala mdoc
// Keep existing age, take the incoming name
val incoming = Person(1, "Alicia", 99)
run(
  for {
    _ <- Mirra.upsertWith(personsL)(_.id, (ex, inc) => ex.copy(name = inc.name), incoming)
    r <- Mirra.all(personsL)
  } yield r,
  three
)
```

`upsertManyWith` handles a list; `upsertManyWith_` additionally returns the post-merge elements.

---

## Query syntax

Extension methods on `Mirra[D, F[A]]` from `MirraSyntax`. They transform or aggregate the *result* of a program without issuing additional state reads — the state is already read by the preceding `all` (or any other state operation returning a collection).

### `filter`

Keeps only elements satisfying a predicate.

```scala mdoc
run(Mirra.all(personsL).filter(_.age > 28), three)
```

### `collect`

Applies a partial function and discards non-matches — like `filter` + `select` in one step.

```scala mdoc
run(
  Mirra.all(personsL).collect { case p if p.age > 28 => p.name },
  three
)
```

### `headOption`

Returns the first element of the result, or `None` if empty.

```scala mdoc
run(Mirra.all(personsL).headOption, three)
run(Mirra.all(personsL).filter(_.age > 99).headOption, three)
```

### `select`

Maps every element — the SQL `SELECT` equivalent.

```scala mdoc
run(Mirra.all(personsL).select(_.name), three)
```

### `size`

Returns the number of elements.

```scala mdoc
run(Mirra.all(personsL).size, three)
```

### `sumBy`

Sums a numeric field across all elements.

```scala mdoc
run(Mirra.all(personsL).sumBy(_.age), three)
```

### `minBy` / `maxBy`

Returns the element with the smallest or largest value of a key, or `None` if empty.

```scala mdoc
run(Mirra.all(personsL).minBy(_.age), three)
run(Mirra.all(personsL).maxBy(_.age), three)
```

### `reduced`

Folds all elements using their `cats.Monoid` instance. Works naturally for strings, numbers, etc.

```scala mdoc
run(Mirra.all(personsL).select(_.name).reduced, three)
```

### `groupBy`

Partitions results into a `Map` keyed by the output of a function.

```scala mdoc
val dave = Person(4, "Dave", 30)
val four = Universe(List(alice, bob, carol, dave), Nil)
run(Mirra.all(personsL).groupBy(_.age), four)
```

### `sortBy` / `sortByDesc`

Sorts results ascending or descending by a key. Requires a `cats.Order` instance for the key type, which `cats.implicits.*` provides for all primitives.

```scala mdoc
run(Mirra.all(personsL).sortBy(_.age), three)
run(Mirra.all(personsL).sortByDesc(_.age), three)
```

### `innerJoin` / `leftJoin` / `rightJoin`

Joins the current result collection with another collection read from the universe via a lens. The join predicate is `(A, B) => Boolean`.

```scala mdoc
val withOrders = Universe(List(alice, bob, carol), List(Order(1, 1, 100), Order(2, 2, 200)))

// inner join: only persons who have a matching order
run(
  Mirra.all(personsL)
    .innerJoin(ordersL)((p, o) => p.id == o.personId),
  withOrders
)
```

```scala mdoc
// left join: all persons, paired with their order if one exists
run(
  Mirra.all(personsL)
    .leftJoin(ordersL)((p, o) => p.id == o.personId),
  withOrders
)
```

```scala mdoc
// right join: all orders, paired with their person if one matches
run(
  Mirra.all(personsL)
    .rightJoin(ordersL)((p, o) => p.id == o.personId),
  withOrders
)
```

### `limit` / `offset`

`limit(n)` returns at most `n` elements from the start of the result; `offset(n)` skips the first `n`. Chain them to implement pagination.

```scala mdoc
// Page 2 of size 1, sorted by age ascending
run(
  Mirra.all(personsL)
    .sortBy(_.age)
    .offset(1)
    .limit(1),
  three
)
```

---

## Combining operations

Because `Mirra[D, *]` is a `Monad`, you can sequence any number of operations in a `for`-comprehension. State modifications accumulate across steps:

```scala mdoc
run(
  for {
    _      <- Mirra.insertMany(personsL)(List(alice, bob, carol))
    _      <- Mirra.delete(personsL)(_.age < 28)
    _      <- Mirra.upsertWith(personsL)(_.id, (ex, inc) => ex.copy(name = inc.name), Person(1, "Alicia", 0))
    result <- Mirra.all(personsL).sortBy(_.age)
  } yield result
)
```
