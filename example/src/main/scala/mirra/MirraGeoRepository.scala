package mirra

object MirraGeoRepository extends GeoRepository[[A] =>> Mirra[Unit, A]] {

  // WGS84 arithmetic mean radius R1 = (2a + b) / 3; used by PostGIS 3.x ST_DistanceSphere
  private val EarthRadiusMeters = 6371008.7714

  def setup: Mirra[Unit, Unit] = Mirra.unit

  def distanceSphere(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Mirra[Unit, Double] =
    Mirra.succeed(compute(lon1, lat1, lon2, lat2))

  private def compute(lon1: Double, lat1: Double, lon2: Double, lat2: Double): Double = {
    val dLon    = math.toRadians(lon2 - lon1)
    val lat1R   = math.toRadians(lat1)
    val lat2R   = math.toRadians(lat2)
    val cosLat1 = math.cos(lat1R); val sinLat1 = math.sin(lat1R)
    val cosLat2 = math.cos(lat2R); val sinLat2 = math.sin(lat2R)
    val cosDLon = math.cos(dLon)
    // Exact formula used by PostGIS ST_DistanceSphere (atan2 form, more numerically stable than acos).
    val a1 = cosLat2 * math.sin(dLon)
    val a2 = cosLat1 * sinLat2 - sinLat1 * cosLat2 * cosDLon
    val a  = math.sqrt(a1 * a1 + a2 * a2)
    val b  = sinLat1 * sinLat2 + cosLat1 * cosLat2 * cosDLon
    math.atan2(a, b) * EarthRadiusMeters
  }
}
