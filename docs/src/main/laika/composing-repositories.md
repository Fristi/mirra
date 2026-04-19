# Composing Repositories

Real applications usually have more than one repository. Mirra handles multi-repository system under tests naturally: wrap the individual repositories in a single higher-kinded trait, derive `FunctorK` and `SemigroupalK` for it, and pass it to `MirraSuite` or `MirraZIOSuite` as the `Alg` type parameter.

## Requirements

`SystemUnderTest` uses `SemigroupalK` to run both the real implementation and the in-memory model through the same program simultaneously, and `FunctorK` to adapt effect types. For the composed trait's instances to be derivable by cats-tagless, **every sub-repository must already have `FunctorK` and `SemigroupalK` instances** — cats-tagless delegates to them when deriving instances for the outer trait.

## 1. Give each sub-repository FunctorK and SemigroupalK

Every repository in the composition needs both instances in its companion object. cats-tagless derives them automatically:

```scala mdoc
import cats.tagless.{Derive, FunctorK, SemigroupalK}
import java.util.UUID

final case class Person(id: UUID, name: String, age: Int)
final case class Organization(id: UUID, name: String)

trait PersonRepository[F[_]] {
  def create: F[Unit]
  def insertMany(persons: List[Person]): F[Long]
  def listAll(): F[List[Person]]
}

object PersonRepository {
  given FunctorK[PersonRepository]    = Derive.functorK
  given SemigroupalK[PersonRepository] = Derive.semigroupalK
}

trait OrganizationRepository[F[_]] {
  def create: F[Unit]
  def insertMany(orgs: List[Organization]): F[Long]
  def listAll(): F[List[Organization]]
}

object OrganizationRepository {
  given FunctorK[OrganizationRepository]    = Derive.functorK
  given SemigroupalK[OrganizationRepository] = Derive.semigroupalK
}
```

## 2. Define the composed algebra

Create a trait whose methods return the individual repositories parameterised by the same `F[_]`, then derive `FunctorK` and `SemigroupalK` for it:

```scala mdoc
trait Repositories[F[_]] {
  def persons: PersonRepository[F]
  def organizations: OrganizationRepository[F]
}

object Repositories {
  given FunctorK[Repositories]    = Derive.functorK
  given SemigroupalK[Repositories] = Derive.semigroupalK
}
```

`Derive.functorK` produces `FunctorK[Repositories]` by applying `FunctorK[PersonRepository].mapK` and `FunctorK[OrganizationRepository].mapK` to the respective fields. `Derive.semigroupalK` does the same by calling `SemigroupalK[PersonRepository].productK` and `SemigroupalK[OrganizationRepository].productK`. This is why the sub-repository instances are required: without them, the macro has no way to transform or pair the nested algebras.

## 3. Expand the universe

Each entity collection lives in the shared `Universe` state type. Add a field for every repository:

```scala mdoc
import mirra.Mirra
import monocle.Focus

final case class Universe(
  persons: List[Person],
  organizations: List[Organization],
)

object Universe {
  def zero: Universe = Universe(Nil, Nil)
}
```

## 4. Implement the Mirra model

Implement `Repositories[[A] =>> Mirra[Universe, A]]`. Each method returns an anonymous implementation of the corresponding sub-repository, targeting its own lens into `Universe`:

```scala mdoc
object MirraRepositories extends Repositories[[A] =>> Mirra[Universe, A]] {
  def persons: PersonRepository[[A] =>> Mirra[Universe, A]] =
    new PersonRepository[[A] =>> Mirra[Universe, A]] {
      def create: Mirra[Universe, Unit] =
        Mirra.unit
      def insertMany(ps: List[Person]): Mirra[Universe, Long] =
        Mirra.insertMany(Focus[Universe](_.persons))(ps)
      def listAll(): Mirra[Universe, List[Person]] =
        Mirra.all(Focus[Universe](_.persons))
    }

  def organizations: OrganizationRepository[[A] =>> Mirra[Universe, A]] =
    new OrganizationRepository[[A] =>> Mirra[Universe, A]] {
      def create: Mirra[Universe, Unit] =
        Mirra.unit
      def insertMany(orgs: List[Organization]): Mirra[Universe, Long] =
        Mirra.insertMany(Focus[Universe](_.organizations))(orgs)
      def listAll(): Mirra[Universe, List[Organization]] =
        Mirra.all(Focus[Universe](_.organizations))
    }
}
```

## 5. Wire into MirraSuite

Set the `Alg` type parameter to `Repositories`. The `bootstrapSystemUnderTest` method receives a real `Repositories[TransactionEffect]` implementation alongside `MirraRepositories`, wired into a single `SystemUnderTest`. Inside `assertMirroring`, access each repository through the paired interpreter:

```scala
class RepositoriesSpec extends MirraSuite[IO, Repositories] {

  override type BootstrapContext     = Containers
  override type MirraState           = Universe
  override type TransactionEffect[A] = ConnectionIO[A]

  override def bootstrapSystemUnderTest(c: Containers): Resource[IO, SystemUnderTest] =
    Resource.pure(new SystemUnderTest(
      Universe.zero,
      DoobieRepositories,  // real implementations
      MirraRepositories,   // in-memory model
      DoobieSupport.rollbackTrans[IO](...)
    ))

  test("insert persons and orgs, then list both") {
    PropF.forAllF { (persons: List[Person], orgs: List[Organization]) =>
      withContainers { c =>
        assertMirroring(c) { x =>
          for {
            _ <- x.persons.create
            _ <- x.organizations.create
            _ <- x.persons.insertMany(persons)
            _ <- x.organizations.insertMany(orgs)
            p <- x.persons.listAll()
            o <- x.organizations.listAll()
          } yield (p, o)
        }
      }
    }
  }
}
```

The real `DoobieRepositories` implementation follows the same pattern as the single-repository Doobie examples — implement each sub-repository against the database and assemble them into the composed trait.
