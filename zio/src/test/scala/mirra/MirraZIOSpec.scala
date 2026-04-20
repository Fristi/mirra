package mirra

import cats.tagless.{Derive, FunctorK}
import monocle.Lens
import zio.*
import zio.test.*

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

object MirraZIOSpec extends ZIOSpecDefault {

  // Each test calls .provide(freshLayer) — ZIO rebuilds the layer per test,
  // giving every test its own Ref[Store].
  def freshLayer: ULayer[ItemRepo[Task]] =
    MirraZIO.layer(MirraItemRepo, Store.empty)

  def spec = suite("MirraZIO")(

    test("insert adds an item and returns 1") {
      ZIO.serviceWithZIO[ItemRepo[Task]] { repo =>
        for {
          count <- repo.insert(Item(1, "foo"))
          all   <- repo.getAll
        } yield assertTrue(count == 1L, all == List(Item(1, "foo")))
      }
    }.provide(freshLayer),

    test("mutations are visible across subsequent calls") {
      ZIO.serviceWithZIO[ItemRepo[Task]] { repo =>
        for {
          _ <- repo.insert(Item(1, "a"))
          _ <- repo.insert(Item(2, "b"))
          all <- repo.getAll
        } yield assertTrue(all == List(Item(1, "a"), Item(2, "b")))
      }
    }.provide(freshLayer),

    test("delete removes matching items and returns count") {
      ZIO.serviceWithZIO[ItemRepo[Task]] { repo =>
        for {
          _     <- repo.insert(Item(1, "a"))
          _     <- repo.insert(Item(2, "b"))
          count <- repo.delete(1)
          all   <- repo.getAll
        } yield assertTrue(count == 1L, all == List(Item(2, "b")))
      }
    }.provide(freshLayer),

    test("delete on absent id returns 0 and leaves state unchanged") {
      ZIO.serviceWithZIO[ItemRepo[Task]] { repo =>
        for {
          _     <- repo.insert(Item(1, "a"))
          count <- repo.delete(99)
          all   <- repo.getAll
        } yield assertTrue(count == 0L, all == List(Item(1, "a")))
      }
    }.provide(freshLayer),

    test("each freshLayer call produces independent state") {
      ZIO.scoped {
        for {
          repo1 <- MirraZIO.layer(MirraItemRepo, Store.empty).build.map(_.get[ItemRepo[Task]])
          repo2 <- MirraZIO.layer(MirraItemRepo, Store.empty).build.map(_.get[ItemRepo[Task]])
          _     <- repo1.insert(Item(1, "a"))
          all1  <- repo1.getAll
          all2  <- repo2.getAll
        } yield assertTrue(all1 == List(Item(1, "a")), all2 == List.empty)
      }
    },

  )
}
