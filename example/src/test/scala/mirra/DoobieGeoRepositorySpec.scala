package mirra

import cats.effect.{IO, Resource}
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import munit.Compare
import org.scalacheck.Gen
import org.scalacheck.effect.PropF
import org.testcontainers.utility.DockerImageName

class DoobieGeoRepositorySpec extends MirraMunitSuite[IO, GeoRepository] with TestContainerForAll {

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def(
    dockerImageName = DockerImageName.parse("postgis/postgis:15-3.3"),
    databaseName = "testcontainer-scala",
    username = "scala",
    password = "scala"
  )

  override type BootstrapContext    = Containers
  override type MirraState          = Unit
  override type TransactionEffect   = ConnectionIO

  override def bootstrapSystemUnderTest(c: Containers): Resource[IO, SystemUnderTest] =
    Resource.pure(
      new SystemUnderTest(
        (),
        DoobieGeoRepository,
        MirraGeoRepository,
        DoobieSupport.rollbackTrans[IO]("org.postgresql.Driver", c.jdbcUrl, c.username, c.password)
      )
    )

  // PostGIS and our Scala formula may differ by sub-metre rounding; 1 m tolerance is sufficient.
  given Compare[Double, Double] with
    def isEqual(a: Double, b: Double): Boolean = math.abs(a - b) < 1.0

  private val genLon = Gen.choose(-180.0, 180.0)
  private val genLat = Gen.choose(-90.0, 90.0)

  test("distanceSphere matches PostGIS ST_DistanceSphere") {
    PropF.forAllF(genLon, genLat, genLon, genLat) { (lon1, lat1, lon2, lat2) =>
      withContainers { container =>
        assertMirroring(container) { x =>
          for {
            _ <- x.setup
            d <- x.distanceSphere(lon1, lat1, lon2, lat2)
          } yield d
        }
      }
    }
  }
}
