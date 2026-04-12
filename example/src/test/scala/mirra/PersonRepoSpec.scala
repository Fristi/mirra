package mirra

import cats.effect.IO
import cats.implicits._
import com.softwaremill.diffx.generic.auto._
import magnolify.scalacheck.auto._
import doobie._
import doobie.implicits._
import org.specs2.mutable.Specification

class PersonRepoSpec extends Specification with DoobieSpec {

  def harnass: Harnass[PersonRepository, IO, ConnectionIO, Universe] =
    new Harnass(Universe.zero, DoobiePersonRepository, MirraPersonRepository, xa.trans)

  "PersonRepository" should {
    "should insert and read" in {
      prop { persons: List[Person] =>
        assertMirroring {
          harnass.model.eval { x =>
            x.create *>
              x.insertMany(persons) *>
              x.listAll()
          }
        }
      }
    }

    "should delete people older then" in {
      prop { (persons: List[Person], age: Int) =>
        assertMirroring {
          harnass.model.eval { x =>
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
