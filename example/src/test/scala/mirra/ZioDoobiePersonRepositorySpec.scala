package mirra

import com.dimafeng.testcontainers.PostgreSQLContainer
import doobie.*
import org.testcontainers.utility.DockerImageName
import cats.implicits.*
import zio.*
import zio.interop.catz.*
import zio.test.*

object ZioDoobiePersonRepositorySpec extends MirraZIOSuite[PersonRepository] {

  // ── abstract type bindings ────────────────────────────────────────────────

  override type BootstrapContext     = PostgreSQLContainer
  override type MirraState           = Universe
  override type TransactionEffect[A] = ConnectionIO[A]

  // ── generators ───────────────────────────────────────────────────────────

  private val genPerson: Gen[Any, Person] =
    for {
      id   <- Gen.uuid
      name <- Gen.stringBounded(1, 50)(Gen.alphaNumericChar)
      age  <- Gen.int(1, 120)
    } yield Person(id, name, age)

  // ── infrastructure ────────────────────────────────────────────────────────

  /** Start a PostgreSQL container once for the whole suite; stop it on teardown. */
  private val containerLayer: ZLayer[Any, Throwable, PostgreSQLContainer] =
    ZLayer.scoped {
      ZIO.acquireRelease(
        ZIO.attemptBlocking {
          val c = PostgreSQLContainer(
            dockerImageNameOverride = DockerImageName.parse("postgres:15.1"),
            databaseName    = "testcontainer-scala",
            username        = "scala",
            password        = "scala",
          )
          c.start()
          c
        }
      )(c => ZIO.attemptBlocking(c.stop()).orDie)
    }

  /** Each `SystemUnderTest` wraps a fresh rollback-transaction so the database
    * is clean after every property-test iteration without restarting the container.
    */
  override def bootstrapSystemUnderTest(c: PostgreSQLContainer): ZIO[Scope, Throwable, SystemUnderTest] =
    ZIO.attempt {
      new SystemUnderTest(
        Universe.zero,
        DoobiePersonRepository,
        MirraPersonRepository,
        DoobieSupport.rollbackTrans[Task](
          "org.postgresql.Driver",
          c.jdbcUrl,
          c.username,
          c.password,
        ),
      )
    }

  // ── spec ─────────────────────────────────────────────────────────────────

  def spec =
    suite("ZioDoobiePersonRepositorySpec")(

      test("should insert and read") {
        ZIO.serviceWithZIO[PostgreSQLContainer] { container =>
          check(Gen.listOf(genPerson)) { persons =>
            assertMirroring(container) { x =>
              for {
                _ <- x.create
                _ <- x.insertMany(persons)
                r <- x.listAll()
              } yield r
            }
          }
        }
      },

      test("should delete people older than") {
        ZIO.serviceWithZIO[PostgreSQLContainer] { container =>
          check(Gen.listOf(genPerson).zip(Gen.int(0, 200))) { case (persons, age) =>
            assertMirroring(container) { x =>
              for {
                _ <- x.create
                _ <- x.insertMany(persons)
                _ <- x.deleteWhenOlderThen(age)
                r <- x.listAll()
              } yield r
            }
          }
        }
      },

    ).provideShared(containerLayer)
}
