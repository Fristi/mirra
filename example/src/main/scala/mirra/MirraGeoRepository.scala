package mirra

object MirraGeoRepository extends GeoRepository[[A] =>> Mirra[Unit, A]] {

  // Same mean Earth radius PostGIS uses for ST_DistanceSphere
  private val EarthRadiusMeters = 6370986.0

  def setup: Mirra[Unit, Unit] = Mirra.unit

  def distanceSphere(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Mirra[Unit, Double] =
    Mirra.succeed(compute(lon1, lat1, lon2, lat2))

  private def compute(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double = {
    println(s"compute start ($lon1, $lat1) .. ($lon2, $lat2)")

    val lat1R = math.toRadians(lat1)
    val lat2R = math.toRadians(lat2)
    val dLon  = math.toRadians(lon2 - lon1)
    // Spherical law of cosines — same formula as PostGIS ST_DistanceSphere.
    // Clamp to [-1, 1] to guard against floating-point values just outside that range.
    val cosAngle = (math.sin(lat1R) * math.sin(lat2R) +
                    math.cos(lat1R) * math.cos(lat2R) * math.cos(dLon))
      .max(-1.0).min(1.0)
    val res = math.acos(cosAngle) * EarthRadiusMeters
    println(s"compute done ($lon1, $lat1) .. ($lon2, $lat2) -> $res")
    MirraGeoRepository
  }
}
