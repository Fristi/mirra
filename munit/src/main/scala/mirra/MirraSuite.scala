package mirra

import cats.Functor
import cats.implicits.*
import munit.{Compare, Location, ScalaCheckSuite}

trait MirraSuite[F[_] : Functor] extends ScalaCheckSuite {

//  def harness[K[_[_]], Tx[_], D]: Harness[K, F, Tx, D]

  def assertMirroring[A](tuple: F[(A, A)])(implicit C: Compare[A, A], L: Location): F[Unit] =
    tuple.map { case (left, right) => assertEquals(left, right) }

}
