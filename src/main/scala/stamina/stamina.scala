import scala.reflect._

package object stamina {
  type ToPersisted = PartialFunction[AnyRef, Persisted]
  type FromPersisted = PartialFunction[Persisted, AnyRef]

  /**  */
  type ByteString = akka.util.ByteString
  val ByteString = akka.util.ByteString

  /**
   * Creates a basic, single-version persister using the specified key and
   * an implicit Encoding. The default version is 1.
   */
  def persisterOld[T <: AnyRef: Encoding: ClassTag](key: String, version: Int = 1): Persister = {
    val encoding = implicitly[Encoding[T]]

    Persister(
      toPersisted = {
        case t: T ⇒ Persisted(key, version, encoding.encode(t))
      },
      fromPersisted = {
        case Persisted(k, v, bytes) if k == key && v == version ⇒ encoding.decode(bytes)
      }
    )
  }

  //TODO: a version of persister for case objects?

}

package stamina {
  /**
   * Marker trait for classes that should be persisted using the StaminaAkkaSerializer.
   *
   * Unforntunately we still need to indicate to Akka which classes should be persisted
   * with which Serializer. This marker trait can be used to mark all your top-level
   * persistable classes (i.e. events, snapshots, etc.) so that you will only need a few
   * lines of configuration in your application.conf, namely:
   *
   * akka.actor.serializers.stamina = <FQCN of your subclass of StaminaAkkaSerializer>
   * akka.actor.serialization-bindings {
   *   "stamina.Persistable" = stamina
   * }
   *
   */
  trait Persistable extends java.io.Serializable

  /**
   * A simple container holding a persistence key, a version number, and the raw
   * serialized/encoded bytes.
   */
  case class Persisted(key: String, version: Int, bytes: ByteString)

  object Persisted {
    /** The default version is 1. */
    def apply(key: String, bytes: ByteString): Persisted = apply(key, 1, bytes)
  }

  /**
   *
   */
  case class Persister(toPersisted: ToPersisted, fromPersisted: FromPersisted) {
    def ||(other: Persister) = orElse(other)
    def orElse(other: Persister): Persister = Persister(
      this.toPersisted orElse other.toPersisted,
      this.fromPersisted orElse other.fromPersisted
    )

    def canPersist(obj: AnyRef): Boolean = toPersisted.isDefinedAt(obj)
    def canRecover(persisted: Persisted): Boolean = fromPersisted.isDefinedAt(persisted)
  }

  object Persister {
    def apply(first: Persister, rest: Persister*): Persister = {
      rest.foldLeft(first)((persisters, persister) ⇒ persisters orElse persister)
    }
  }

  case class UnregistredTypeException(obj: AnyRef)
    extends RuntimeException(s"No persister registered for class: ${obj.getClass}")

  case class UnregisteredKeyException(key: String)
    extends RuntimeException(s"No persister registered for key: ${key}")

  case class UnrecoverableDataException(persisted: Persisted, error: Throwable)
    extends RuntimeException(s"Error while trying to read persisted data with key '${persisted.key}' and version ${persisted.version}. Cause: ${error}")
}
