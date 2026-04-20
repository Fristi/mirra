package mirra

import cats.effect.IO
import cats.tagless.{Derive, FunctorK}
import monocle.Lens
import munit.CatsEffectSuite

// ---- test domain ----

case class Item(id: Int, value: String)
case class Store(items: List[Item])

object Store {
  val items: Lens[Store, List[Item]] = Lens[Store, List[Item]](_.items)(v => _.copy(items = v))
  val empty: Store = Store(Nil)
}

trait ItemRepo[F[_]] {
  def insert(item: Item): F[Long]
  def getAll: F[List[Item]]
  def delete(id: Int): F[Long]
}

object ItemRepo {
  given FunctorK[ItemRepo] = Derive.functorK
}

object MirraItemRepo extends ItemRepo[[A] =>> Mirra[Store, A]] {
  def insert(item: Item): Mirra[Store, Long]  = Mirra.insert(Store.items)(item)
  def getAll: Mirra[Store, List[Item]]        = Mirra.all(Store.items)
  def delete(id: Int): Mirra[Store, Long]     = Mirra.delete(Store.items)(_.id == id)
}

// ---- spec ----

class MirraCatsEffectSpec extends CatsEffectSuite {

  def mkRepo: IO[ItemRepo[IO]] =
    MirraCatsEffect.make[IO, ItemRepo, Store](MirraItemRepo, Store.empty)

  test("insert adds an item and returns 1") {
    for {
      repo  <- mkRepo
      count <- repo.insert(Item(1, "foo"))
      all   <- repo.getAll
    } yield {
      assertEquals(count, 1L)
      assertEquals(all, List(Item(1, "foo")))
    }
  }

  test("mutations are visible across subsequent calls") {
    for {
      repo <- mkRepo
      _    <- repo.insert(Item(1, "a"))
      _    <- repo.insert(Item(2, "b"))
      all  <- repo.getAll
    } yield assertEquals(all, List(Item(1, "a"), Item(2, "b")))
  }

  test("delete removes matching items and returns count") {
    for {
      repo  <- mkRepo
      _     <- repo.insert(Item(1, "a"))
      _     <- repo.insert(Item(2, "b"))
      count <- repo.delete(1)
      all   <- repo.getAll
    } yield {
      assertEquals(count, 1L)
      assertEquals(all, List(Item(2, "b")))
    }
  }

  test("delete on absent id returns 0 and leaves state unchanged") {
    for {
      repo  <- mkRepo
      _     <- repo.insert(Item(1, "a"))
      count <- repo.delete(99)
      all   <- repo.getAll
    } yield {
      assertEquals(count, 0L)
      assertEquals(all, List(Item(1, "a")))
    }
  }

  test("each make call produces independent state") {
    for {
      repo1 <- mkRepo
      repo2 <- mkRepo
      _     <- repo1.insert(Item(1, "a"))
      all1  <- repo1.getAll
      all2  <- repo2.getAll
    } yield {
      assertEquals(all1, List(Item(1, "a")))
      assertEquals(all2, List.empty)
    }
  }
}
