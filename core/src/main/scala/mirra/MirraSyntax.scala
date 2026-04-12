package mirra

import cats.{Foldable, Functor, FunctorFilter, Monoid}
import cats.implicits._
import monocle.Lens

trait MirraSyntax {

  implicit class RichMirra[F[_], D, A](val mirra: Mirra[D, F[A]]) {
    def headOption(implicit F: Foldable[F]): Mirra[D, Option[A]] =
      Mirra(mirra.db.map(_.get(0)))

    def filter(f: A => Boolean)(implicit F: FunctorFilter[F]): Mirra[D, F[A]] =
      Mirra(mirra.db.map(_.filter(f)))

    def select[B](f: A => B)(implicit F: Functor[F]): Mirra[D, F[B]] =
      Mirra(mirra.db.map(_.map(f)))

    def collect[B](f: PartialFunction[A, B])(implicit F: FunctorFilter[F]): Mirra[D, F[B]] =
      Mirra(mirra.db.map(_.collect(f)))

    def size(implicit F: Foldable[F]): Mirra[D, Long] =
      Mirra(mirra.db.map(_.size))

    def reduced(implicit M: Monoid[A], F: Foldable[F]): Mirra[D, A] =
      Mirra(mirra.db.map(_.foldLeft(Monoid[A].empty)(Monoid[A].combine)))

    def leftJoin[B, C](other: Lens[D, List[B]])(join: (A, B) => Boolean)(implicit F: Foldable[F]): Mirra[D, List[(A, Option[B])]] =
      Mirra {
        for {
          x <- mirra.db
          y <- Mirra.all(other).db
        } yield x.toList.map(a => (a, y.find(b => join(a, b))))
      }

    def rightJoin[B, C](other: Lens[D, List[B]])(join: (A, B) => Boolean)(implicit F: Foldable[F]): Mirra[D, List[(Option[A], B)]] =
      Mirra {
        for {
          x <- mirra.db
          y <- Mirra.all(other).db
        } yield y.map(b => (x.find(a => join(a, b)), b))
      }

    def innerJoin[B, C](other: Lens[D, List[B]])(join: (A, B) => Boolean)(implicit F: Foldable[F]): Mirra[D, List[(A, B)]] = {
      Mirra {
        for {
          x <- mirra.db
          y <- Mirra.all(other).db
        } yield x.toList.flatMap(l => y.filter(r => join(l, r)).map(r => (l, r)))
      }

    }
  }

}
