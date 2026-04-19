package mirra

import cats.implicits.*
import monocle.Lens
import munit.FunSuite

// ---- test domain ----

case class Item(id: Int, name: String, value: Int)
case class World(items: List[Item], tags: List[String])

object World {
  val items: Lens[World, List[Item]] = Lens[World, List[Item]](_.items)(v => _.copy(items = v))
  val tags: Lens[World, List[String]]  = Lens[World, List[String]](_.tags)(v => _.copy(tags = v))
  val empty: World = World(Nil, Nil)
}

// ---- spec ----

class MirraSpec extends FunSuite with MirraSyntax {

  // helpers
  private def run[A](m: Mirra[World, A], state: World = World.empty): A = m.run(state)

  private val a = Item(1, "a", 10)
  private val b = Item(2, "b", 20)
  private val c = Item(3, "c", 30)

  // ------------------------------------------------------------------
  // Core operators
  // ------------------------------------------------------------------

  test("unit returns unit and does not modify state") {
    val result = run(Mirra.unit[World])
    assertEquals(result, ())
  }

  test("succeed wraps a pure value without touching state") {
    assertEquals(run(Mirra.succeed[World, Int](42)), 42)
    assertEquals(run(Mirra.succeed[World, String]("hello")), "hello")
  }

  test("all returns the full collection from the lens") {
    val state = World(List(a, b), Nil)
    assertEquals(run(Mirra.all(World.items), state), List(a, b))
  }

  test("all returns empty list when collection is empty") {
    assertEquals(run(Mirra.all(World.items)), List.empty[Item])
  }

  // ------------------------------------------------------------------
  // insert / insertMany
  // ------------------------------------------------------------------

  test("insert adds one item and returns 1") {
    assertEquals(run(Mirra.insert(World.items)(a)), 1L)
  }

  test("insertMany returns the count of inserted items") {
    assertEquals(run(Mirra.insertMany(World.items)(List(a, b, c))), 3L)
  }

  test("insertMany returns 0 for empty list") {
    assertEquals(run(Mirra.insertMany(World.items)(Nil)), 0L)
  }

  test("insertMany_ returns the inserted items") {
    assertEquals(run(Mirra.insertMany_(World.items)(List(a, b))), List(a, b))
  }

  test("insertMany appends to existing items") {
    val state = World(List(a), Nil)
    val result = run(
      for {
        _ <- Mirra.insert(World.items)(b)
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result, List(a, b))
  }

  // ------------------------------------------------------------------
  // update / update_
  // ------------------------------------------------------------------

  test("update modifies matching items and returns count") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.update(World.items)(_.value > 15, x => x.copy(value = 0)), state), 2L)
  }

  test("update returns 0 when nothing matches") {
    val state = World(List(a, b), Nil)
    assertEquals(run(Mirra.update(World.items)(_.id == 99, x => x.copy(name = "x")), state), 0L)
  }

  test("update_ returns the items before the update is applied") {
    val state = World(List(a, b), Nil)
    val result = run(Mirra.update_(World.items)(_.id == 1, _.copy(name = "updated")), state)
    assertEquals(result, List(a))
  }

  test("update_ applies transformation to matching items in state") {
    val state = World(List(a, b), Nil)
    val result = run(
      for {
        _ <- Mirra.update_(World.items)(_.id == 1, _.copy(name = "updated"))
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result.find(_.id == 1).map(_.name), Some("updated"))
  }

  test("update does not touch non-matching items") {
    val state = World(List(a, b, c), Nil)
    val result = run(
      for {
        _ <- Mirra.update(World.items)(_.id == 1, _.copy(name = "changed"))
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result.find(_.id == 2), Some(b))
    assertEquals(result.find(_.id == 3), Some(c))
  }

  // ------------------------------------------------------------------
  // delete / delete_
  // ------------------------------------------------------------------

  test("delete removes matching items and returns count") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.delete(World.items)(_.value > 15), state), 2L)
  }

  test("delete returns 0 when nothing matches") {
    val state = World(List(a, b), Nil)
    assertEquals(run(Mirra.delete(World.items)(_.id == 99), state), 0L)
  }

  test("delete_ returns the deleted items") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.delete_(World.items)(_.value > 15), state), List(b, c))
  }

  test("delete removes items from state") {
    val state = World(List(a, b, c), Nil)
    val result = run(
      for {
        _ <- Mirra.delete(World.items)(_.value > 15)
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result, List(a))
  }

  // ------------------------------------------------------------------
  // truncate
  // ------------------------------------------------------------------

  test("truncate clears all items and returns previous count") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.truncate(World.items), state), 3L)
  }

  test("truncate leaves state empty") {
    val state = World(List(a, b), Nil)
    val result = run(
      for {
        _ <- Mirra.truncate(World.items)
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result, List.empty[Item])
  }

  test("truncate on empty collection returns 0") {
    assertEquals(run(Mirra.truncate(World.items)), 0L)
  }

  // ------------------------------------------------------------------
  // upsert / upsertMany / upsertMany_
  // ------------------------------------------------------------------

  test("upsert inserts a new item when no conflict exists") {
    assertEquals(run(Mirra.upsert(World.items)(_.id, identity, a)), 1L)
  }

  test("upsert updates an existing item on conflict") {
    val updated = a.copy(name = "updated")
    val state = World(List(a, b), Nil)
    val result = run(
      for {
        _ <- Mirra.upsert(World.items)(_.id, _ => updated, a)
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result.find(_.id == 1).map(_.name), Some("updated"))
  }

  test("upsert does not duplicate on conflict") {
    val state = World(List(a), Nil)
    val result = run(
      for {
        _ <- Mirra.upsert(World.items)(_.id, identity, a)
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result.length, 1)
  }

  test("upsertMany inserts all new items") {
    assertEquals(run(Mirra.upsertMany(World.items)(_.id, identity, List(a, b, c))), 3L)
  }

  test("upsertMany updates conflicts and inserts new") {
    val state = World(List(a), Nil)
    val updatedA = a.copy(name = "changed")
    val result = run(
      for {
        _ <- Mirra.upsertMany(World.items)(_.id, x => if x.id == 1 then updatedA else x, List(a, b))
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result.length, 2)
    assertEquals(result.find(_.id == 1).map(_.name), Some("changed"))
    assertEquals(result.find(_.id == 2), Some(b))
  }

  test("upsertMany_ returns inserted and updated items") {
    val state = World(List(a), Nil)
    val result = run(Mirra.upsertMany_(World.items)(_.id, identity, List(a, b)), state)
    // b is inserted, a is updated (same value via identity)
    assertEquals(result.map(_.id).toSet, Set(1, 2))
  }

  // ------------------------------------------------------------------
  // upsertWith / upsertManyWith / upsertManyWith_
  // ------------------------------------------------------------------

  test("upsertWith inserts a new item when no conflict exists") {
    assertEquals(run(Mirra.upsertWith(World.items)(_.id, (_, inc) => inc, a)), 1L)
  }

  test("upsertWith merges only selected fields on conflict") {
    // existing: a = Item(1, "a", 10); incoming has same id but new name and value
    val incoming = Item(1, "updated", 99)
    val state = World(List(a, b), Nil)
    // merge: keep existing value, take incoming name — like SET name = EXCLUDED.name
    val result = run(
      for {
        _ <- Mirra.upsertWith(World.items)(_.id, (ex, inc) => ex.copy(name = inc.name), incoming)
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result.find(_.id == 1).map(_.name), Some("updated"))
    assertEquals(result.find(_.id == 1).map(_.value), Some(10)) // value untouched
  }

  test("upsertWith does not duplicate on conflict") {
    val state = World(List(a), Nil)
    val result = run(
      for {
        _ <- Mirra.upsertWith(World.items)(_.id, (_, inc) => inc, a)
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result.length, 1)
  }

  test("upsertManyWith inserts all new items") {
    assertEquals(run(Mirra.upsertManyWith(World.items)(_.id, (_, inc) => inc, List(a, b, c))), 3L)
  }

  test("upsertManyWith merges conflicts and inserts new items") {
    val state = World(List(a), Nil)
    // incoming a has same id; merge keeps existing value, takes incoming name
    val incomingA = Item(1, "merged", 99)
    val result = run(
      for {
        _ <- Mirra.upsertManyWith(World.items)(_.id, (ex, inc) => ex.copy(name = inc.name), List(incomingA, b))
        xs <- Mirra.all(World.items)
      } yield xs,
      state
    )
    assertEquals(result.length, 2)
    assertEquals(result.find(_.id == 1).map(_.name), Some("merged"))
    assertEquals(result.find(_.id == 1).map(_.value), Some(10)) // value untouched
    assertEquals(result.find(_.id == 2), Some(b))
  }

  test("upsertManyWith_ returns inserted items and post-merge updated items") {
    val state = World(List(a), Nil)
    val incomingA = Item(1, "merged", 99)
    // a conflicts (updated), b is new (inserted)
    val result = run(Mirra.upsertManyWith_(World.items)(_.id, (_, inc) => inc, List(incomingA, b)), state)
    assertEquals(result.map(_.id).toSet, Set(1, 2))
    assertEquals(result.find(_.id == 1).map(_.name), Some("merged"))
  }

  test("upsertManyWith_ returns post-merge state, not pre-merge state") {
    val state = World(List(a), Nil)
    // merge: take incoming name but keep existing value
    val incoming = Item(1, "new-name", 999)
    val result = run(Mirra.upsertManyWith_(World.items)(_.id, (ex, inc) => ex.copy(name = inc.name), List(incoming)), state)
    assertEquals(result.find(_.id == 1).map(_.name), Some("new-name"))
    assertEquals(result.find(_.id == 1).map(_.value), Some(10))
  }

  // ------------------------------------------------------------------
  // Monad laws / composition
  // ------------------------------------------------------------------

  test("flatMap sequences state transformations") {
    val result = run(
      for {
        _ <- Mirra.insertMany(World.items)(List(a, b))
        _ <- Mirra.delete(World.items)(_.id == 1)
        xs <- Mirra.all(World.items)
      } yield xs
    )
    assertEquals(result, List(b))
  }

  test("pure / succeed satisfies left identity") {
    val f: Int => Mirra[World, Int] = n => Mirra.succeed(n * 2)
    val left  = run(Mirra.succeed[World, Int](5).flatMap(f))
    val right = run(f(5))
    assertEquals(left, right)
  }

  // ------------------------------------------------------------------
  // MirraSyntax
  // ------------------------------------------------------------------

  test("headOption returns Some for non-empty result") {
    val state = World(List(a, b), Nil)
    assertEquals(run(Mirra.all(World.items).headOption, state), Some(a))
  }

  test("headOption returns None for empty result") {
    assertEquals(run(Mirra.all(World.items).headOption), None)
  }

  test("filter narrows the result collection") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.all(World.items).filter(_.value > 15), state), List(b, c))
  }

  test("select maps over the result collection") {
    val state = World(List(a, b), Nil)
    assertEquals(run(Mirra.all(World.items).select(_.name), state), List("a", "b"))
  }

  test("collect applies a partial function to the result collection") {
    val state = World(List(a, b, c), Nil)
    val result = run(
      Mirra.all(World.items).collect { case i if i.value > 15 => i.name },
      state
    )
    assertEquals(result, List("b", "c"))
  }

  test("size returns the count of elements") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.all(World.items).size, state), 3L)
  }

  test("reduced folds elements using their Monoid") {
    val state = World.empty.copy(tags = List("x", "y", "z"))
    assertEquals(run(Mirra.all(World.tags).reduced, state), "xyz")
  }

  test("leftJoin returns all left items with optional right match") {
    val left  = World(List(a, b), Nil)
    // tags hold strings matching item names
    val state = left.copy(tags = List("a"))
    val result = run(
      Mirra.all(World.items).leftJoin(World.tags)((item, tag) => item.name == tag),
      state
    )
    assertEquals(result, List((a, Some("a")), (b, None)))
  }

  test("rightJoin returns all right items with optional left match") {
    val state = World(List(a), Nil).copy(tags = List("a", "z"))
    val result = run(
      Mirra.all(World.items).rightJoin(World.tags)((item, tag) => item.name == tag),
      state
    )
    assertEquals(result, List((Some(a), "a"), (None, "z")))
  }

  test("innerJoin returns only matching pairs") {
    val state = World(List(a, b), Nil).copy(tags = List("a", "z"))
    val result = run(
      Mirra.all(World.items).innerJoin(World.tags)((item, tag) => item.name == tag),
      state
    )
    assertEquals(result, List((a, "a")))
  }

  // ------------------------------------------------------------------
  // sortBy / sortByDesc
  // ------------------------------------------------------------------

  test("sortBy orders elements ascending by key") {
    val state = World(List(c, a, b), Nil)
    assertEquals(run(Mirra.all(World.items).sortBy(_.value), state), List(a, b, c))
  }

  test("sortByDesc orders elements descending by key") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.all(World.items).sortByDesc(_.value), state), List(c, b, a))
  }

  test("sortBy on empty collection returns empty list") {
    assertEquals(run(Mirra.all(World.items).sortBy(_.value)), List.empty[Item])
  }

  // ------------------------------------------------------------------
  // minBy / maxBy
  // ------------------------------------------------------------------

  test("minBy returns the element with the smallest key") {
    val state = World(List(b, a, c), Nil)
    assertEquals(run(Mirra.all(World.items).minBy(_.value), state), Some(a))
  }

  test("maxBy returns the element with the largest key") {
    val state = World(List(a, c, b), Nil)
    assertEquals(run(Mirra.all(World.items).maxBy(_.value), state), Some(c))
  }

  test("minBy returns None on empty collection") {
    assertEquals(run(Mirra.all(World.items).minBy(_.value)), None)
  }

  test("maxBy returns None on empty collection") {
    assertEquals(run(Mirra.all(World.items).maxBy(_.value)), None)
  }

  // ------------------------------------------------------------------
  // sumBy
  // ------------------------------------------------------------------

  test("sumBy sums extracted numeric values") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.all(World.items).sumBy(_.value), state), 60)
  }

  test("sumBy returns zero on empty collection") {
    assertEquals(run(Mirra.all(World.items).sumBy(_.value)), 0)
  }

  // ------------------------------------------------------------------
  // groupBy
  // ------------------------------------------------------------------

  test("groupBy partitions elements by key") {
    val d = Item(4, "a", 40)
    val state = World(List(a, b, c, d), Nil)
    val result = run(Mirra.all(World.items).groupBy(_.name), state)
    assertEquals(result, Map("a" -> List(a, d), "b" -> List(b), "c" -> List(c)))
  }

  test("groupBy on empty collection returns empty map") {
    assertEquals(run(Mirra.all(World.items).groupBy(_.name)), Map.empty[String, List[Item]])
  }

  // ------------------------------------------------------------------
  // limit / offset
  // ------------------------------------------------------------------

  test("limit returns at most n elements") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.all(World.items).limit(2), state), List(a, b))
  }

  test("limit larger than collection returns all elements") {
    val state = World(List(a, b), Nil)
    assertEquals(run(Mirra.all(World.items).limit(10), state), List(a, b))
  }

  test("limit(0) returns empty list") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.all(World.items).limit(0), state), List.empty[Item])
  }

  test("limit on empty collection returns empty list") {
    assertEquals(run(Mirra.all(World.items).limit(5)), List.empty[Item])
  }

  test("offset skips the first n elements") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.all(World.items).offset(1), state), List(b, c))
  }

  test("offset larger than collection returns empty list") {
    val state = World(List(a, b), Nil)
    assertEquals(run(Mirra.all(World.items).offset(10), state), List.empty[Item])
  }

  test("offset(0) returns all elements") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.all(World.items).offset(0), state), List(a, b, c))
  }

  test("offset on empty collection returns empty list") {
    assertEquals(run(Mirra.all(World.items).offset(3)), List.empty[Item])
  }

  test("offset then limit acts as a page window") {
    val state = World(List(a, b, c), Nil)
    assertEquals(run(Mirra.all(World.items).offset(1).limit(1), state), List(b))
  }

  test("sortBy then offset then limit returns correct page") {
    val state = World(List(c, a, b), Nil)
    assertEquals(run(Mirra.all(World.items).sortBy(_.value).offset(1).limit(2), state), List(b, c))
  }
}
