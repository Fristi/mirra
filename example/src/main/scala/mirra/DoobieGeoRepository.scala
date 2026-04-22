package mirra

import doobie._
import doobie.implicits.*
import doobie.postgres.implicits.*
import cats.implicits.*

object DoobieGeoRepository extends GeoRepository[ConnectionIO] {

  def setup: ConnectionIO[Unit] =
    fr"CREATE EXTENSION IF NOT EXISTS postgis".update.run.void

  def distanceSphere(lon1: Double, lat1: Double, lon2: Double, lat2: Double): ConnectionIO[Double] =
    fr"SELECT ST_DistanceSphere(ST_MakePoint($lon1, $lat1), ST_MakePoint($lon2, $lat2))"
      .query[Double]
      .unique
}
