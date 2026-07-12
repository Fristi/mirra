package mirra

import cats.tagless.{Derive, FunctorK, SemigroupalK}

trait GeoRepository[F[_]] {
  def setup: F[Unit]
  def distanceSphere(lon1: Double, lat1: Double, lon2: Double, lat2: Double): F[Double]
}

object GeoRepository {
  given FunctorK[GeoRepository]   = Derive.functorK
  given SemigroupalK[GeoRepository] = Derive.semigroupalK
}
