package mirra

import cats.effect.IO
import cats.implicits.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.free.connection
import doobie.implicits.*
import doobie.util.transactor.Strategy
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}
import org.testcontainers.utility.DockerImageName

class PersonRepoSpec extends CatsEffectSuite with ScalaCheckEffectSuite with MirraSuite[IO] with TestContainerForAll {

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

  test("should insert and read") {

      PropF.forAllF { (persons: List[Person]) =>
        withContainers { (c: Containers) =>

        val trans = DoobieSupport.rollbackTrans[IO]("org.postgresql.Driver", c.jdbcUrl, c.username, c.password)

        def algebraUnderTest =
          new AlgebraUnderTest(Universe.zero, DoobiePersonRepository, MirraPersonRepository, trans)

        assertMirroring {
          algebraUnderTest.model.eval { x =>
            x.create *>
              x.insertMany(persons) *>
              x.listAll()
          }
        }
      }
    }
  }

  test("should delete people older then") {
    PropF.forAllF { (persons: List[Person], age: Int) =>
      withContainers { (c: Containers) =>

        val trans = DoobieSupport.rollbackTrans[IO]("org.postgresql.Driver", c.jdbcUrl, c.username, c.password)

        def algebraUnderTest =
          new AlgebraUnderTest(Universe.zero, DoobiePersonRepository, MirraPersonRepository, trans)

        assertMirroring {
          algebraUnderTest.model.eval { x =>
            x.create *>
              x.insertMany(persons) *>
              x.deleteWhenOlderThen(age) *>
              x.listAll()
          }
        }
      }
    }
  }
}