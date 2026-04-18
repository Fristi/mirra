package mirra

import cats.effect.{IO, Resource}
import cats.implicits.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}
import org.testcontainers.utility.DockerImageName

class DoobiePersonRepositorySpec extends MirraSuite[IO, PersonRepository] with TestContainerForAll {

  given Arbitrary[Person] = Arbitrary {
    for {
      id <- Gen.uuid
      name <- Gen.stringOfN(50, Gen.alphaChar)
      age <- Gen.posNum[Int]
    } yield Person(id, name, age)
  }

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def(
    dockerImageName = DockerImageName.parse("postgres:15.1"),
    databaseName = "testcontainer-scala",
    username = "scala",
    password = "scala"
  )

  override type BootstrapContext = Containers
  override type MirraState = Universe
  override type TransactionEffect = ConnectionIO

  override def bootstrapSystemUnderTest(c: BootstrapContext): Resource[IO, SystemUnderTest] =
    Resource.pure(new SystemUnderTest(Universe.zero, DoobiePersonRepository, MirraPersonRepository, DoobieSupport.rollbackTrans[IO]("org.postgresql.Driver", c.jdbcUrl, c.username, c.password)))

  test("should insert and read") {
      PropF.forAllF { (persons: List[Person]) =>
        withContainers { container =>
          assertMirroring(container) { x =>
            for {
              _ <- x.create
              _ <- x.insertMany(persons)
              r <- x.listAll()
            } yield r
          }
        }
      }
  }

  test("should delete people older then") {
    PropF.forAllF { (persons: List[Person], age: Int) =>
      withContainers { container =>
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
  }
}