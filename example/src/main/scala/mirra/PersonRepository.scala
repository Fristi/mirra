package mirra

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
  given FunctorK[PersonRepository] = Derive.functorK
  given SemigroupalK[PersonRepository] = Derive.semigroupalK
}
