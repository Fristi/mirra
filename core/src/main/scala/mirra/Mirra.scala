package mirra

import cats.Monad
import cats.data.State
import cats.implicits._
import monocle.Lens

/** An in-memory state monad for modelling CRUD operations.
 *
 * `Mirra[D, A]` wraps `State[D, A]`, where `D` is the in-memory domain/state
 * type and `A` is the result type. Use [[Mirra$]] companion operators to build
 * programs that read and modify collections inside `D` via Monocle [[Lens]]es,
 * then run them with [[run]].
 *
 * @tparam D  the domain state type (e.g. a case class holding several lists)
 * @tparam A  the result type
 */
final case class Mirra[D, A] (db: State[D, A]) {
  /** Executes the program against `state` and returns the result, discarding
   *  the final state. */
  def run(state: D): A = db.runA(state).value
}

/** Companion object providing CRUD operators and a [[cats.Monad]] instance. */
object Mirra {

  /** Returns all elements at the collection targeted by `at`. */
  def all[D, A](at: Lens[D, List[A]]): Mirra[D, List[A]] =
    Mirra(State.get.map(at.get))

  /** Returns `Unit` without modifying state.  Useful as a no-op in
   *  `for`-comprehensions (e.g. to model a schema-creation step that is a
   *  no-op in memory). */
  def unit[D]: Mirra[D, Unit] =
    Mirra(State.pure(()))

  /** Lifts a pure value into `Mirra` without modifying state. */
  def succeed[D, A](value: A): Mirra[D, A] =
    Mirra(State.pure(value))

  /** Appends `elements` to the collection at `at` and returns the number of
   *  inserted elements.
   *
   *  @see [[insertMany_]] to also receive the inserted items. */
  def insertMany[D, A](at: Lens[D, List[A]])(elements: List[A]): Mirra[D, Long] =
    insertMany_(at)(elements).size

  /** Appends `elements` to the collection at `at` and returns the inserted
   *  elements. */
  def insertMany_[D, A](at: Lens[D, List[A]])(elements: List[A]): Mirra[D, List[A]] =
    Mirra(State.modify[D](s => at.modify(_ ++ elements)(s)) *> State.pure(elements))

  /** Appends a single `element` to the collection at `at` and returns `1L`. */
  def insert[D, A](at: Lens[D, List[A]])(element: A): Mirra[D, Long] =
    insertMany(at)(List(element))

  /** Applies `update` to every element matching `filter` and returns the
   *  number of affected rows.
   *
   *  @see [[update_]] to also receive the affected items. */
  def update[D, A](at: Lens[D, List[A]])(filter: A => Boolean, update: A => A): Mirra[D, Long] =
    update_(at)(filter, update).size

  /** Applies `update` to every element matching `filter`, replaces them in
   *  state, and returns the matching elements *before* the transformation. */
  def update_[D, A](at: Lens[D, List[A]])(filter: A => Boolean, update: A => A): Mirra[D, List[A]] =
    Mirra {
      for {
        elements <- State.get[D]
        (toUpdate, notToUpdate) = at.get(elements).partition(filter)
        _ <- State.modify[D](s => at.modify(_ => toUpdate.map(update) ++ notToUpdate)(s))
      } yield toUpdate
    }

  /** Removes every element matching `filter` and returns the number of deleted
   *  elements.
   *
   *  @see [[delete_]] to also receive the deleted items. */
  def delete[D, A](at: Lens[D, List[A]])(filter: A => Boolean): Mirra[D, Long] =
    delete_(at)(filter).size

  /** Removes every element matching `filter` from state and returns the
   *  removed elements. */
  def delete_[D, A](at: Lens[D, List[A]])(filter: A => Boolean): Mirra[D, List[A]] =
    Mirra {
      for {
        elements <- State.get[D]
        (toDelete, toKeep) = at.get(elements).partition(filter)
        _ <- State.modify[D](s => at.modify(_ => toKeep)(s))
      } yield toDelete
    }

  /** Removes all elements from the collection at `at` and returns the number
   *  of elements that existed before the truncation. */
  def truncate[D, A](at: Lens[D, List[A]]): Mirra[D, Long] =
    Mirra {
      for {
        elements <- State.get[D]
        _ <- State.modify[D](d => at.modify(_ => List.empty)(d))
      } yield at.get(elements).size
    }

  /** Upserts a single `item` into the collection at `at`.
   *
   *  Uses `conflict` to compute a conflict key.  If an existing element has
   *  the same key, `update` is applied to it; otherwise `item` is appended.
   *  Returns the number of affected rows (always `1L`).
   *
   *  @see [[upsertMany]] for bulk upsert. */
  def upsert[D, A, B](at: Lens[D, List[A]])(conflict: A => B, update: A => A, item: A): Mirra[D, Long] =
    upsertMany(at)(conflict, update, List(item))

  /** Upserts `items` into the collection at `at` using `conflict` as the
   *  conflict key.  Returns the total number of affected rows (inserts +
   *  updates).
   *
   *  @see [[upsertMany_]] to also receive the affected items. */
  def upsertMany[D, A, B](at: Lens[D, List[A]])(conflict: A => B, update: A => A, items: List[A]): Mirra[D, Long] =
    upsertMany_(at)(conflict, update, items).size

  /** Upserts `items` into the collection at `at` using `conflict` as the
   *  conflict key and returns the affected items (newly inserted items and the
   *  pre-update versions of updated items). */
  def upsertMany_[D, A, B](at: Lens[D, List[A]])(conflict: A => B, update: A => A, items: List[A]): Mirra[D, List[A]] =
    Mirra {
      for {
        elements <- State.get[D]
        conflicting = items.map(conflict)
        (toUpdate, notToUpdate) = at.get(elements).partition(x => conflicting.contains(conflict(x)))
        updated = toUpdate.map(update)
        conflictedUpdated = toUpdate.map(conflict)
        toInsert = items.filterNot(x => conflictedUpdated.contains(conflict(x)))
        _ <- State.modify[D](s => at.modify(_ => updated ++ notToUpdate ++ toInsert)(s))
      } yield toInsert ++ updated
    }

  /** [[cats.Monad]] instance for `Mirra[D, *]`, enabling `for`-comprehension
   *  sequencing of operations over the same domain `D`. */
  implicit def monad[D]: Monad[[A] =>> Mirra[D, A]] = new (Monad[[A] =>> Mirra[D, A]]) {
    def pure[A](x: A): Mirra[D, A] = succeed(x)

    def flatMap[A, B](fa: Mirra[D, A])(f: A => Mirra[D, B]): Mirra[D, B] =
      Mirra(fa.db.flatMap(a => f(a).db))

    def tailRecM[A, B](a: A)(f: A => Mirra[D, Either[A, B]]): Mirra[D, B] =
      flatMap(f(a)) {
        case Left(a) => tailRecM(a)(f)
        case Right(b) => succeed(b)
      }
  }

}
