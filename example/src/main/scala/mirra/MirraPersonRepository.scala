package mirra

import monocle.Focus


final case class Universe(
                           persons: List[Person]
                         )

object Universe {
  def zero: Universe = Universe(Nil)

  val personsL = Focus[Universe](_.persons)
}

object MirraPersonRepository extends PersonRepository[[A] =>> Mirra[Universe, A]] {
  def insertMany(persons: List[Person]): Mirra[Universe, Long] =
    Mirra.insertMany(Universe.personsL)(persons)

  def deleteWhenOlderThen(age: Long): Mirra[Universe, Long] =
    Mirra.delete(Universe.personsL)(_.age > age)

  def listAll(): Mirra[Universe, List[Person]] =
    Mirra.all(Universe.personsL)

  def create: Mirra[Universe, Unit] =
    Mirra.unit
}
