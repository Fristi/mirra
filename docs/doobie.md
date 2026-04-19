# Doobie

The `doobie` module provides `DoobieSupport.rollbackTrans`, a natural transformation `ConnectionIO ~> F` that runs each property-test iteration inside a transaction that is **always rolled back**. This keeps the database clean between iterations without restarting the container.

```scala
libraryDependencies += "io.github.fristi" %% "mirra-doobie" % "<version>"
```

## Implementation

The effect type is `ConnectionIO` — Doobie's own connection action type. Implement your algebra against it as you normally would:

```scala mdoc:invisible
import cats.tagless.{Derive, FunctorK, SemigroupalK}
import java.util.UUID

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
```

```scala mdoc
import cats.implicits._
import doobie._
import doobie.implicits._

object DoobiePersonRepository extends PersonRepository[ConnectionIO] {
  def create: ConnectionIO[Unit] =
    fr"""create table if not exists persons (
      |  id uuid primary key,
      |  name varchar(50) not null,
      |  age numeric not null
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

## Rollback transactor

`DoobieSupport.rollbackTrans` builds a `Transactor` whose strategy always rolls back, regardless of success or failure. Pass it as the `tx` argument to `SystemUnderTest`:

```scala
DoobieSupport.rollbackTrans[IO](
  driver   = "org.postgresql.Driver",
  jdbcUrl  = container.jdbcUrl,
  username = container.username,
  password = container.password,
)
// res: ConnectionIO ~> IO
```

See [munit](munit.md) or [ZIO Test](zio-test.md) for full wiring examples.
