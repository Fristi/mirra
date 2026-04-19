# munit + cats-effect

The `munit` module provides `MirraSuite[F[_], Alg[_[_]]]` — a trait that wires `CatsEffectSuite` and `ScalaCheckEffectSuite` together with Mirra's mirror-testing machinery.

```scala
libraryDependencies += "io.github.fristi" %% "mirra-munit" % "<version>"
```

## How to use

Extend `MirraSuite` and supply three abstract types:

| Type | Purpose | Example |
|---|---|---|
| `BootstrapContext` | What the test receives from infrastructure | `Containers` (testcontainers) |
| `MirraState` | The in-memory universe type | `Universe` |
| `TransactionEffect[_]` | The real DB effect | `ConnectionIO` |

Implement `bootstrapSystemUnderTest` to wire both interpreters and a rollback transactor into a `SystemUnderTest`. Then call `assertMirroring(context) { x => … }` in each test — `x` is your algebra running both interpreters simultaneously.

Each property iteration runs inside a transaction that is **always rolled back**, so the database stays clean without restarting the container.

## Full example

```scala mdoc:invisible
import cats.tagless.{Derive, FunctorK, SemigroupalK}
import java.util.UUID
import mirra.Mirra
import monocle.Focus
import doobie._
import doobie.implicits._
import doobie.postgres.implicits._

final case class Person(id: UUID, name: String, age: Int)

trait PersonRepository[F[_]] {
  def create: F[Unit]
  def insertMany(persons: List[Person]): F[Long]
  def deleteWhenOlderThen(age: Long): F[Long]
  def listAll(): F[List[Person]]
}

object PersonRepository {
  implicit val functorK: FunctorK[PersonRepository]         = Derive.functorK
  implicit val semigroupalK: SemigroupalK[PersonRepository] = Derive.semigroupalK
}

final case class Universe(persons: List[Person])
object Universe { def zero: Universe = Universe(Nil) }

object MirraPersonRepository extends PersonRepository[[A] =>> Mirra[Universe, A]] {
  def create: Mirra[Universe, Unit] = Mirra.unit
  def insertMany(persons: List[Person]): Mirra[Universe, Long] =
    Mirra.insertMany(Focus[Universe](_.persons))(persons)
  def deleteWhenOlderThen(age: Long): Mirra[Universe, Long] =
    Mirra.delete(Focus[Universe](_.persons))(_.age > age)
  def listAll(): Mirra[Universe, List[Person]] =
    Mirra.all(Focus[Universe](_.persons))
}

object DoobiePersonRepository extends PersonRepository[ConnectionIO] {
  def create: ConnectionIO[Unit] =
    fr"""create table if not exists persons (
      |  id uuid primary key, name varchar(50) not null, age numeric not null
      |)""".stripMargin.update.run.void
  def insertMany(persons: List[Person]): ConnectionIO[Long] =
    Update[Person]("insert into persons (id, name, age) values (?, ?, ?)")
      .updateMany(persons).map(_.toLong)
  def deleteWhenOlderThen(age: Long): ConnectionIO[Long] =
    fr"delete from persons where age > $age".update.run.map(_.toLong)
  def listAll(): ConnectionIO[List[Person]] =
    fr"select id, name, age from persons".query[Person].to[List]
}
```

```scala mdoc:compile-only
import cats.effect.{IO, Resource}
import cats.implicits.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
import mirra.{DoobieSupport, MirraSuite}
import org.scalacheck.effect.PropF
import org.scalacheck.{Arbitrary, Gen}
import org.testcontainers.utility.DockerImageName

class DoobiePersonRepositorySpec
    extends MirraSuite[IO, PersonRepository]
    with TestContainerForAll {

  given Arbitrary[Person] = Arbitrary {
    for {
      id   <- Gen.uuid
      name <- Gen.stringOfN(50, Gen.alphaChar)
      age  <- Gen.posNum[Int]
    } yield Person(id, name, age)
  }

  override val containerDef: PostgreSQLContainer.Def = PostgreSQLContainer.Def(
    dockerImageName = DockerImageName.parse("postgres:15.1"),
    databaseName    = "testcontainer-scala",
    username        = "scala",
    password        = "scala"
  )

  override type BootstrapContext     = Containers
  override type MirraState           = Universe
  override type TransactionEffect[A] = ConnectionIO[A]

  override def bootstrapSystemUnderTest(c: Containers): Resource[IO, SystemUnderTest] =
    Resource.pure(new SystemUnderTest(
      Universe.zero,
      DoobiePersonRepository,
      MirraPersonRepository,
      DoobieSupport.rollbackTrans[IO]("org.postgresql.Driver", c.jdbcUrl, c.username, c.password)
    ))

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

  test("should delete people older than") {
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
```

Notice there is no assertion logic about _what_ the result should be. No filtering, no manual comparison. Just: "do both implementations agree?" The framework generates the inputs, runs both interpreters, and `assertMirroring` diffs the outputs.
