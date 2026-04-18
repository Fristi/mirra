package mirra

import monocle.Focus
import monocle.macros.syntax


final case class Universe(
  persons: List[Person]
)

object Universe {
  def zero: Universe = Universe(Nil)
}

object MirraPersonRepository extends PersonRepository[[A] =>> Mirra[Universe, A]] {
  def insertMany(persons: List[Person]): Mirra[Universe, Long] =
    Mirra.insertMany(Focus[Universe](_.persons))(persons)

  def deleteWhenOlderThen(age: Long): Mirra[Universe, Long] =
    Mirra.delete(Focus[Universe](_.persons))(_.age > age)

  def listAll(): Mirra[Universe, List[Person]] =
    Mirra.all(Focus[Universe](_.persons))

  def create: Mirra[Universe, Unit] =
    Mirra.unit
}
