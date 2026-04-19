package mirra

import cats.{Foldable, Functor, FunctorFilter, Monoid, Order}
import cats.implicits._
import monocle.Lens

/** Syntax extensions for `Mirra[D, F[A]]` — operations that transform or
 *  combine the *result* of a `Mirra` program when that result is itself a
 *  collection `F[A]`.
 *
 *  Mix this trait into your test suite or module to bring the extensions into
 *  scope:
 *  {{{
 *  class MySpec extends FunSuite with MirraSyntax { ... }
 *  }}}
 */
trait MirraSyntax {

  /** Extensions available on any `Mirra[D, F[A]]` where `F` is a collection. */
  implicit class RichMirra[F[_], D, A](val mirra: Mirra[D, F[A]]) {

    /** Returns the first element of the result collection, or `None` if empty.
     *
     *  Note: delegates to `get(0)` on the underlying Scala collection, which
     *  is `O(n)` for linked structures. */
    def headOption(implicit F: Foldable[F]): Mirra[D, Option[A]] =
      Mirra(mirra.db.map(_.get(0)))

    /** Keeps only elements satisfying `f`. */
    def filter(f: A => Boolean)(implicit F: FunctorFilter[F]): Mirra[D, F[A]] =
      Mirra(mirra.db.map(_.filter(f)))

    /** Transforms each element with `f` (analogous to `map`; named `select`
     *  to mirror SQL `SELECT` semantics). */
    def select[B](f: A => B)(implicit F: Functor[F]): Mirra[D, F[B]] =
      Mirra(mirra.db.map(_.map(f)))

    /** Applies a partial function to each element, discarding non-matches
     *  (analogous to `collectFirst` but collecting all matches). */
    def collect[B](f: PartialFunction[A, B])(implicit F: FunctorFilter[F]): Mirra[D, F[B]] =
      Mirra(mirra.db.map(_.collect(f)))

    /** Returns the number of elements in the result collection. */
    def size(implicit F: Foldable[F]): Mirra[D, Long] =
      Mirra(mirra.db.map(_.size))

    /** Folds all elements using their [[cats.Monoid]] instance. */
    def reduced(implicit M: Monoid[A], F: Foldable[F]): Mirra[D, A] =
      Mirra(mirra.db.map(_.foldLeft(Monoid[A].empty)(Monoid[A].combine)))

    /** Left join with another collection at `other`.
     *
     *  For each element `a` in the left (this) collection, finds at most one
     *  matching element `b` in the right collection using `join(a, b)`.
     *  Returns `(a, Some(b))` when a match exists, `(a, None)` otherwise. */
    def leftJoin[B, C](other: Lens[D, List[B]])(join: (A, B) => Boolean)(implicit F: Foldable[F]): Mirra[D, List[(A, Option[B])]] =
      Mirra {
        for {
          x <- mirra.db
          y <- Mirra.all(other).db
        } yield x.toList.map(a => (a, y.find(b => join(a, b))))
      }

    /** Right join with another collection at `other`.
     *
     *  For each element `b` in the right collection at `other`, finds at most
     *  one matching element `a` in the left (this) collection using
     *  `join(a, b)`.  Returns `(Some(a), b)` when a match exists, `(None, b)`
     *  otherwise. */
    def rightJoin[B, C](other: Lens[D, List[B]])(join: (A, B) => Boolean)(implicit F: Foldable[F]): Mirra[D, List[(Option[A], B)]] =
      Mirra {
        for {
          x <- mirra.db
          y <- Mirra.all(other).db
        } yield y.map(b => (x.find(a => join(a, b)), b))
      }

    /** Inner join with another collection at `other`.
     *
     *  Returns a pair `(a, b)` for every combination where `join(a, b)` is
     *  true.  Elements with no match on either side are excluded. */
    def innerJoin[B, C](other: Lens[D, List[B]])(join: (A, B) => Boolean)(implicit F: Foldable[F]): Mirra[D, List[(A, B)]] = {
      Mirra {
        for {
          x <- mirra.db
          y <- Mirra.all(other).db
        } yield x.toList.flatMap(l => y.filter(r => join(l, r)).map(r => (l, r)))
      }

    }

    /** Returns elements sorted ascending by the key produced by `f`. */
    def sortBy[B: Order](f: A => B)(implicit F: Foldable[F]): Mirra[D, List[A]] =
      Mirra(mirra.db.map(_.toList.sortWith((x, y) => Order[B].lt(f(x), f(y)))))

    /** Returns elements sorted descending by the key produced by `f`. */
    def sortByDesc[B: Order](f: A => B)(implicit F: Foldable[F]): Mirra[D, List[A]] =
      Mirra(mirra.db.map(_.toList.sortWith((x, y) => Order[B].gt(f(x), f(y)))))

    /** Returns the element with the smallest value of `f`, or `None` if empty. */
    def minBy[B: Order](f: A => B)(implicit F: Foldable[F]): Mirra[D, Option[A]] =
      Mirra(mirra.db.map(xs => F.minimumByOption(xs)(f)))

    /** Returns the element with the largest value of `f`, or `None` if empty. */
    def maxBy[B: Order](f: A => B)(implicit F: Foldable[F]): Mirra[D, Option[A]] =
      Mirra(mirra.db.map(xs => F.maximumByOption(xs)(f)))

    /** Sums the numeric values produced by `f` across all elements. */
    def sumBy[B](f: A => B)(implicit N: Numeric[B], F: Foldable[F]): Mirra[D, B] =
      Mirra(mirra.db.map(_.toList.foldLeft(N.zero)((acc, a) => N.plus(acc, f(a)))))

    /** Groups elements by the key produced by `f`. */
    def groupBy[B](f: A => B)(implicit F: Foldable[F]): Mirra[D, Map[B, List[A]]] =
      Mirra(mirra.db.map(_.toList.groupBy(f)))

    /** Returns at most `n` elements from the start of the result collection. */
    def limit(n: Int)(implicit F: Foldable[F]): Mirra[D, List[A]] =
      Mirra(mirra.db.map(_.toList.take(n)))

    /** Skips the first `n` elements of the result collection. */
    def offset(n: Int)(implicit F: Foldable[F]): Mirra[D, List[A]] =
      Mirra(mirra.db.map(_.toList.drop(n)))
  }

}
