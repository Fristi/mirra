package mirra

import cats.data.Kleisli
import cats.effect.Async
import cats.implicits.*
import skunk.*
import skunk.codec.all.*
import skunk.data.Completion
import skunk.implicits.*

class SkunkPersonRepository[F[_]: Async] extends PersonRepository[([A] =>> SkunkTransaction[F, A])] {

  private val personCodec: Codec[Person] =
    (uuid ~ text ~ int4).imap { case id ~ name ~ age => Person(id, name, age) }(p =>
      p.id ~ p.name ~ p.age
    )

  private val createCommand: Command[Void] =
    sql"""
      CREATE TABLE IF NOT EXISTS persons (
        id uuid PRIMARY KEY,
        name text NOT NULL,
        age int4 NOT NULL
      )
    """.command

  private val insertCommand: Command[Person] =
    sql"INSERT INTO persons (id, name, age) VALUES ($personCodec)".command

  private val listAllQuery: Query[Void, Person] =
    sql"SELECT id, name, age FROM persons".query(personCodec)

  private val deleteOlderThanCommand: Command[Long] =
    sql"DELETE FROM persons WHERE age > ${int8}".command

  def create: SkunkTransaction[F, Unit] =
    Kleisli(_.execute(createCommand).void)

  def insertMany(persons: List[Person]): SkunkTransaction[F, Long] = Kleisli { session =>
    session.prepareR(insertCommand).use(pc => persons.traverse(pc.execute).map(_.length.toLong))
  }

  def deleteWhenOlderThen(age: Long): SkunkTransaction[F, Long] = Kleisli { session =>
    session.prepareR(deleteOlderThanCommand).use(_.execute(age).map {
      case Completion.Delete(n) => n.toLong
      case _                   => 0L
    })
  }

  def listAll(): SkunkTransaction[F, List[Person]] =
    Kleisli(_.execute(listAllQuery))
}

object SkunkPersonRepository {
  def apply[F[_]: Async]: SkunkPersonRepository[F] = new SkunkPersonRepository[F]
}
