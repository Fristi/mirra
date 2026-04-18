package mirra

import cats.Monad
import cats.data.State
import cats.implicits._
import monocle.Lens

final case class Mirra[D, A] (db: State[D, A]) {
  def run(state: D): A = db.runA(state).value
}

object Mirra {

  def all[D, A](at: Lens[D, List[A]]): Mirra[D, List[A]] =
    Mirra(State.get.map(at.get))

  def unit[D]: Mirra[D, Unit] =
    Mirra(State.pure(()))

  def succeed[D, A](value: A): Mirra[D, A] =
    Mirra(State.pure(value))

  def insertMany[D, A](at: Lens[D, List[A]])(elements: List[A]): Mirra[D, Long] =
    insertMany_(at)(elements).size

  def insertMany_[D, A](at: Lens[D, List[A]])(elements: List[A]): Mirra[D, List[A]] =
    Mirra(State.modify[D](s => at.modify(_ ++ elements)(s)) *> State.pure(elements))

  def insert[D, A](at: Lens[D, List[A]])(element: A): Mirra[D, Long] =
    insertMany(at)(List(element))

  def update[D, A](at: Lens[D, List[A]])(filter: A => Boolean, update: A => A): Mirra[D, Long] =
    update_(at)(filter, update).size

  def update_[D, A](at: Lens[D, List[A]])(filter: A => Boolean, update: A => A): Mirra[D, List[A]] =
    Mirra {
      for {
        elements <- State.get[D]
        (toUpdate, notToUpdate) = at.get(elements).partition(filter)
        _ <- State.modify[D](s => at.modify(_ => toUpdate.map(update) ++ notToUpdate)(s))
      } yield toUpdate
    }

  def delete[D, A](at: Lens[D, List[A]])(filter: A => Boolean): Mirra[D, Long] =
    delete_(at)(filter).size

  def delete_[D, A](at: Lens[D, List[A]])(filter: A => Boolean): Mirra[D, List[A]] =
    Mirra {
      for {
        elements <- State.get[D]
        (toDelete, toKeep) = at.get(elements).partition(filter)
        _ <- State.modify[D](s => at.modify(_ => toKeep)(s))
      } yield toDelete
    }

  def truncate[D, A](at: Lens[D, List[A]]): Mirra[D, Long] =
    Mirra {
      for {
        elements <- State.get[D]
        _ <- State.modify[D](d => at.modify(_ => List.empty)(d))
      } yield at.get(elements).size
    }

  def upsert[D, A, B](at: Lens[D, List[A]])(conflict: A => B, update: A => A, item: A): Mirra[D, Long] =
    upsertMany(at)(conflict, update, List(item))

  def upsertMany[D, A, B](at: Lens[D, List[A]])(conflict: A => B, update: A => A, items: List[A]): Mirra[D, Long] =
    upsertMany_(at)(conflict, update, items).size

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