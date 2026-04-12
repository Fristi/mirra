package mirra

import doobie._
import doobie.implicits._
import cats.implicits._
import doobie.postgres.implicits._

object DoobiePersonRepository extends PersonRepository[ConnectionIO] {

  object queries {
    def deleteWhenOlderThen(age: Long): Update0 =
      fr"delete from persons where age > $age".update

    def create =
      fr"""create table if not exists persons (
        |	id uuid primary key,
        |	name text not null,
        |	age numeric not null
        |)""".stripMargin.update

    def listAll: Query0[Person] =
      fr"select id, name, age from persons".query[Person]
  }

  def insertMany(persons: List[Person]): ConnectionIO[Long] =
    Update[Person]("insert into persons (id, name, age) values (?, ?, ?)").updateMany(persons).map(_.toLong)

  def deleteWhenOlderThen(age: Long): ConnectionIO[Long] =
    queries.deleteWhenOlderThen(age).run.map(_.toLong)

  def listAll(): ConnectionIO[List[Person]] =
    queries.listAll.to[List]

  def create: doobie.ConnectionIO[Unit] =
    queries.create.run.void

}
