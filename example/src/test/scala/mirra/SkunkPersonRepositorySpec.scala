package mirra

import cats.effect.IO
import cats.implicits.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import munit.{CatsEffectSuite, ScalaCheckEffectSuite}
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}
import org.testcontainers.utility.DockerImageName

class SkunkPersonRepositorySpec extends CatsEffectSuite with ScalaCheckEffectSuite with MirraSuite[IO] with TestContainerForAll {

  given Arbitrary[Person] = Arbitrary {
    for {
      id   <- Gen.uuid
      name <- Gen.stringOfN(50, Gen.alphaChar)
      age  <- Gen.posNum[Int]
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
        SkunkSupport.rollbackTrans[IO](
          host     = c.container.getHost,
          port     = c.container.getMappedPort(5432),
          user     = c.username,
          database = c.databaseName,
          password = Some(c.password)
        ).use { trans =>
          def algebraUnderTest =
            new AlgebraUnderTest[PersonRepository, IO, [A] =>> cats.data.Kleisli[IO, skunk.Session[IO], A], Universe](Universe.zero, SkunkPersonRepository[IO], MirraPersonRepository, trans)

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
  }

  test("should delete people older then") {
    PropF.forAllF { (persons: List[Person], age: Int) =>
      withContainers { (c: Containers) =>
        SkunkSupport.rollbackTrans[IO](
          host     = c.container.getHost,
          port     = c.container.getMappedPort(5432),
          user     = c.username,
          database = c.databaseName,
          password = Some(c.password)
        ).use { trans =>
          def algebraUnderTest =
            new AlgebraUnderTest[PersonRepository, IO, [A] =>> cats.data.Kleisli[IO, skunk.Session[IO], A], Universe](Universe.zero, SkunkPersonRepository[IO], MirraPersonRepository, trans)

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
}
