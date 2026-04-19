# Skunk

The `skunk` module provides `SkunkSupport.rollbackTrans`, a natural transformation `Kleisli[F, Session[F], *] ~> F` that runs each property-test iteration inside a Skunk session that is **always rolled back**.

```scala
libraryDependencies += "io.github.fristi" %% "mirra-skunk" % "<version>"
```

## Implementation

The effect type is `Kleisli[F, Session[F], *]` — a function from a live Skunk session to an `F` action. All composed operations share the same session, so they run in the same transaction.

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
import cats.data.Kleisli
import cats.effect.Async
import skunk._
import skunk.codec.all._
import skunk.data.Completion
import skunk.implicits._

class SkunkPersonRepository[F[_]: Async] extends PersonRepository[[A] =>> Kleisli[F, Session[F], A]] {
  private val personCodec: Codec[Person] =
    (uuid ~ text ~ int4).imap { case id ~ name ~ age => Person(id, name, age) }(p =>
      p.id ~ p.name ~ p.age)

  def create: Kleisli[F, Session[F], Unit] =
    Kleisli(_.execute(sql"""
      CREATE TABLE IF NOT EXISTS persons (
        id   uuid PRIMARY KEY,
        name text NOT NULL,
        age  int4 NOT NULL
      )""".command).void)

  def insertMany(persons: List[Person]): Kleisli[F, Session[F], Long] = Kleisli { session =>
    session.prepareR(sql"INSERT INTO persons (id, name, age) VALUES ($personCodec)".command)
      .use(pc => persons.traverse(pc.execute).map(_.length.toLong))
  }

  def deleteWhenOlderThen(age: Long): Kleisli[F, Session[F], Long] = Kleisli { session =>
    session.prepareR(sql"DELETE FROM persons WHERE age > ${int8}".command)
      .use(_.execute(age).map { case Completion.Delete(n) => n.toLong; case _ => 0L })
  }

  def listAll(): Kleisli[F, Session[F], List[Person]] =
    Kleisli(_.execute(sql"SELECT id, name, age FROM persons".query(personCodec)))
}
```

## Rollback session

`SkunkSupport.rollbackTrans` acquires a Skunk session and wraps it in a `Kleisli ~> F` that always rolls back on completion. Pass it as the `tx` argument to `SystemUnderTest` in your test suite.
