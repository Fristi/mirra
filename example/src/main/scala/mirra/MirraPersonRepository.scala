package mirra

import monocle.macros.Lenses

@Lenses
final case class Universe(
  persons: List[Person]
)

object Universe {
  def zero: Universe = Universe(Nil)
}

object MirraPersonRepository extends PersonRepository[Mirra[Universe, *]] {
  def insertMany(persons: List[Person]): Mirra[Universe, Long] =
    Mirra.insertMany(Universe.persons)(persons)

  def deleteWhenOlderThen(age: Long): Mirra[Universe, Long] =
    Mirra.delete(Universe.persons)(_.age > age)

  def listAll(): Mirra[Universe, List[Person]] =
    Mirra.all(Universe.persons)

  def create: Mirra[Universe, Unit] =
    Mirra.unit
}
