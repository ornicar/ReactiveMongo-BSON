package reactivemongo.api.bson

import java.math.{ BigDecimal => JBigDec }

import java.util.UUID

import java.time.Instant

import java.nio.ByteBuffer

import scala.language.implicitConversions

import scala.util.{ Failure, Success, Try }
import scala.util.control.NonFatal

import scala.collection.mutable.{
  Builder => MBuilder,
  HashMap => MMap,
  HashSet => MSet
}

import scala.collection.immutable.{ HashMap, IndexedSeq, LinearSeq }

import buffer._

import exceptions.{ BSONValueNotFoundException, TypeDoesNotMatchException }

sealed trait Producer[T] {
  private[bson] def generate(): Iterable[T]
}

/** A [[https://docs.mongodb.com/manual/reference/bson-types/ BSON]] value */
sealed trait BSONValue { self =>
  /**
   * The code indicating the BSON type for this value
   */
  private[bson] val code: Int

  /**
   * The code indicating the BSON type for this value as Byte
   */
  private[bson] def byteCode: Byte

  /** The number of bytes for the serialized representation */
  private[reactivemongo] def byteSize: Int

  /**
   * Tries to parse this value as a `T` one.
   *
   * {{{
   * import scala.util.Try
   * import reactivemongo.api.bson.BSONValue
   *
   * def foo(v: BSONValue): Try[String] = v.asTry[String]
   * }}}
   */
  final def asTry[T](implicit reader: BSONReader[T]): Try[T] =
    reader.readTry(this) // TODO: as[M[_]] ?

  /**
   * Optionally parses this value as a `T` one.
   *
   * @return `Some` successfully parsed value, or `None` if fails
   *
   * {{{
   * import reactivemongo.api.bson.BSONValue
   *
   * def foo(v: BSONValue): Option[String] = v.asOpt[String]
   * }}}
   */
  @inline final def asOpt[T](implicit reader: BSONReader[T]): Option[T] =
    reader.readOpt(this)

  // --- Optimized/builtin conversions

  @inline private[bson] def asBoolean: Try[Boolean] =
    Failure(TypeDoesNotMatchException(
      "BSONBoolean", self.getClass.getSimpleName))

  @inline private[bson] def asDecimal: Try[BigDecimal] =
    Failure(TypeDoesNotMatchException(
      "BSONDecimal", self.getClass.getSimpleName))

  @inline private[bson] def asDateTime: Try[Instant] =
    Failure(TypeDoesNotMatchException(
      "BSONDateTime", self.getClass.getSimpleName))

  @inline private[bson] def toDouble: Try[Double] =
    Failure(TypeDoesNotMatchException(
      "BSONDouble", self.getClass.getSimpleName))

  @inline private[bson] def toFloat: Try[Float] =
    Failure(TypeDoesNotMatchException(
      "<float>", self.getClass.getSimpleName))

  @inline private[bson] def asLong: Try[Long] =
    Failure(TypeDoesNotMatchException(
      "BSONLong", self.getClass.getSimpleName))

  @inline private[bson] def asInt: Try[Int] =
    Failure(TypeDoesNotMatchException(
      "BSONInteger", self.getClass.getSimpleName))

  @inline private[bson] def asString: Try[String] =
    Failure(TypeDoesNotMatchException(
      "BSONString", self.getClass.getSimpleName))

}

/** [[BSONValue]] factories and utilities */
object BSONValue extends BSONValueLowPriority1 {
  /**
   * Returns a String representation for the value.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONDocument, BSONValue }
   *
   * def foo(v: BSONValue): String = BSONValue.pretty(v)
   *
   * foo(BSONDocument("bar" -> 1))
   * // { "bar": 1 }
   * }}}
   */
  def pretty(value: BSONValue): String = value match {
    case arr: BSONArray => BSONArray.pretty(arr)

    case bin: BSONBinary => BSONBinary.pretty(bin)

    case BSONBoolean(bool) => bool.toString

    case time: BSONDateTime => BSONDateTime.pretty(time)

    case doc: BSONDocument => BSONDocument.pretty(doc)

    case BSONDouble(d) => d.toString

    case BSONInteger(i) => i.toString

    case l: BSONLong => BSONLong.pretty(l)

    case d: BSONDecimal => BSONDecimal.pretty(d)

    case s: BSONString => BSONString.pretty(s)

    case id: BSONObjectID => BSONObjectID.pretty(id)

    case ts: BSONTimestamp => BSONTimestamp.pretty(ts)

    case BSONNull => BSONNull.pretty
    case BSONUndefined => BSONUndefined.pretty
    case BSONMinKey => BSONMinKey.pretty
    case BSONMaxKey => BSONMaxKey.pretty

    case _ => value.toString
  }

  /**
   * An addition operation for [[BSONValue]],
   * so that it forms an additive semigroup with the BSON value kind.
   */
  object Addition extends ((BSONValue, BSONValue) => BSONArray) {
    def apply(x: BSONValue, y: BSONValue): BSONArray = (x, y) match {
      case (a: BSONArray, b: BSONArray) => a ++ b
      case (a: BSONArray, _) => a ++ y
      case (_, b: BSONArray) => x +: b
      case _ => BSONArray(IndexedSeq(x, y))
    }
  }

  implicit def identityValueProducer[B <: BSONValue](value: B): Producer[BSONValue] = new SomeValueProducer(value)

  // ---

  protected final class SomeValueProducer(
    private val value: BSONValue) extends Producer[BSONValue] {
    private[bson] def generate() = Seq(value)
  }
}

// Conversions (for BSONArray factories)
private[bson] sealed trait BSONValueLowPriority1
  extends BSONValueLowPriority2 { _: BSONValue.type =>

  implicit def optionProducer[T](value: Option[T])(implicit writer: BSONWriter[T]): Producer[BSONValue] = value.flatMap(writer.writeOpt) match {
    case Some(bson) => new SomeValueProducer(bson)
    case _ => NoneValueProducer
  }

  implicit val noneProducer: None.type => Producer[BSONValue] =
    _ => NoneValueProducer

  // ---

  protected object NoneValueProducer extends Producer[BSONValue] {
    private val underlying = Seq.empty[BSONValue]
    @inline private[bson] def generate() = underlying
  }
}

private[bson] sealed trait BSONValueLowPriority2 {
  _: BSONValue.type with BSONValueLowPriority1 =>

  implicit def valueProducer[T](value: T)(implicit writer: BSONWriter[T]): Producer[BSONValue] = writer.writeOpt(value) match {
    case Some(v) => new SomeValueProducer(v)
    case _ => NoneValueProducer
  }
}

/**
 * A BSON Double.
 *
 * {{{
 * reactivemongo.api.bson.BSONDouble(1.23D)
 * }}}
 */
final class BSONDouble private[bson] (val value: Double)
  extends BSONValue with BSONNumberLike.Value with BSONBooleanLike.Value {

  val code = 0x01
  val byteCode = 0x01: Byte

  override private[reactivemongo] val byteSize = 8

  override lazy val toDouble: Try[Double] = Success(value)

  override lazy val toFloat: Try[Float] = {
    if (value >= Float.MinValue && value <= Float.MaxValue) {
      Try(value.toFloat)
    } else {
      super.toFloat
    }
  }

  private[bson] override lazy val asLong: Try[Long] = {
    lazy val r = value.round

    @SuppressWarnings(Array("ComparingFloatingPointTypes"))
    def to = {
      if (value.isWhole && r.toDouble == value) {
        Success(r)
      } else super.asLong
    }

    to
  }

  @inline def toLong = Try(value.toLong)

  @inline def toBoolean = Success(value > 0D)

  override lazy val asInt: Try[Int] = {
    if (value.isWhole && value >= Int.MinValue && value <= Int.MaxValue) {
      Try(value.toInt)
    } else {
      super.asInt
    }
  }

  @inline def toInt = Try(value.toInt)

  override private[bson] lazy val asDecimal: Try[BigDecimal] =
    Try(BigDecimal exact value)

  override def hashCode: Int = value.hashCode

  override def equals(that: Any): Boolean = that match {
    case other: BSONDouble => value.compare(other.value) == 0
    case _ => false
  }

  override def toString: String = s"BSONDouble($value)"
}

/**
 * [[BSONDouble]] utilities
 *
 * {{{
 * import reactivemongo.api.bson.BSONDouble
 *
 * BSONDouble(1.23D) match {
 *   case BSONDouble(v) => assert(v == 1.23D)
 *   case _ => ???
 * }
 * }}}
 */
object BSONDouble {
  /**
   * Extracts the double value if `that`'s a [[BSONDouble]].
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONDouble, BSONValue }
   *
   * def patMat(v: BSONValue): Boolean = v match {
   *   case BSONDouble(v) => v > 0D
   *   case _ => false
   * }
   * }}}
   */
  def unapply(that: Any): Option[Double] = that match {
    case bson: BSONDouble => Some(bson.value)
    case _ => None
  }

  /**
   * Returns a [[BSONDouble]].
   *
   * {{{
   * reactivemongo.api.bson.BSONDouble(1.23D)
   * }}}
   */
  @inline def apply(value: Double): BSONDouble = new BSONDouble(value)
}

/**
 * A [[https://docs.mongodb.com/manual/reference/bson-types/#string BSON string]].
 *
 * {{{
 * reactivemongo.api.bson.BSONString("foo")
 * }}}
 */
final class BSONString private[bson] (val value: String) extends BSONValue {
  val code = 0x02
  val byteCode = 0x02: Byte

  override private[reactivemongo] lazy val byteSize = 5 + value.getBytes.size

  override private[reactivemongo] lazy val asString: Try[String] =
    Success(value)

  override def hashCode: Int = value.hashCode

  override def equals(that: Any): Boolean = that match {
    case other: BSONString => value == other.value
    case _ => false
  }

  override def toString: String = s"BSONString($value)"
}

/**
 * [[BSONString]] utilities
 *
 * {{{
 * import reactivemongo.api.bson.BSONString
 *
 * BSONString("foo") match {
 *   case BSONString(v) => v == "foo"
 *   case _ => false
 * }
 * }}}
 */
object BSONString {
  /**
   * Extracts the string value if `that`'s a [[BSONString]].
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONString, BSONValue }
   *
   * def show(v: BSONValue): String = v match {
   *   case BSONString(s) => s
   *   case _ => "not-string"
   * }
   * }}}
   */
  def unapply(that: Any): Option[String] = that match {
    case bson: BSONString => Some(bson.value)
    case _ => None
  }

  /**
   * Returns a [[BSONString]].
   *
   * {{{
   * reactivemongo.api.bson.BSONString("foo")
   * }}}
   */
  @inline def apply(value: String): BSONString = new BSONString(value)

  /** Returns the plain string representation for the given [[BSONString]]. */
  @inline def pretty(str: BSONString): String =
    s"""'${str.value.replaceAll("\'", "\\\\'")}'"""

}

/**
 * A `BSONArray` (type `0x04`) is a indexed sequence of [[BSONValue]].
 *
 * {{{
 * import reactivemongo.api.bson._
 *
 * BSONArray(BSONString("foo"), BSONDouble(1.2D))
 * }}}
 *
 * @define indexParam the index to be found in the array (`0` being the first)
 */
sealed abstract class BSONArray extends BSONValue {
  /** The BSON values */
  def values: IndexedSeq[BSONValue]

  val code = 0x04
  val byteCode = 0x04: Byte

  /**
   * Returns the [[BSONValue]] at the given `index`.
   *
   * If there is no such `index`, `None` is returned.
   *
   * {{{
   * def secondAsString(
   *   a: reactivemongo.api.bson.BSONArray): Option[String] =
   *   a.get(1).flatMap(_.asOpt[String])
   * }}}
   *
   * @param index $indexParam
   */
  def get(index: Int): Option[BSONValue] = values.lift(index)

  /** The first/mandatory value, if any */
  def headOption: Option[BSONValue] = values.headOption

  /**
   * Returns a BSON array with the given value prepended.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONArray, BSONString }
   *
   * BSONString("foo") +: BSONArray(1L) // [ 'foo', NumberLong(1) ]
   * }}}
   */
  def +:(value: BSONValue): BSONArray = BSONArray(value +: values)

  /**
   * Returns a BSON array with the values of the given one appended.
   *
   * {{{
   * import reactivemongo.api.bson.BSONArray
   *
   * val arr1 = BSONArray(1, "foo")
   * val arr2 = BSONArray(2L, "bar")
   *
   * arr1 ++ arr2 // [ 1, 'foo', NumberLong(2), 'bar' ]
   * }}}
   */
  def ++(arr: BSONArray): BSONArray = BSONArray(values ++ arr.values)

  /**
   * Returns a BSON array with the given values appended.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONArray, BSONLong }
   *
   * val arr = BSONArray(1, "foo")
   *
   * arr ++ BSONLong(3L) // [ 1, 'foo', NumberLong(3) ]
   * }}}
   */
  def ++(values: BSONValue*): BSONArray = {
    val vs = IndexedSeq.newBuilder[BSONValue]

    values.foreach { vs += _ }

    BSONArray(this.values ++ vs.result())
  }

  /** The number of values */
  @inline def size = values.size

  /** Indicates whether this array is empty */
  @inline def isEmpty: Boolean = values.isEmpty

  /**
   * Returns the [[BSONValue]] at the given `index`,
   * and converts it with the given implicit [[BSONReader]].
   *
   * If there is no matching value, or the value could not be deserialized,
   * or converted, returns a `None`.
   *
   * {{{
   * import reactivemongo.api.bson.BSONArray
   *
   * val arr = BSONArray(1, "foo")
   *
   * arr.getAsOpt[Int](0) // Some(1)
   * arr.getAsOpt[Int](1) // None; "foo" not int
   * }}}
   *
   * @param index $indexParam
   */
  def getAsOpt[T](index: Int)(implicit reader: BSONReader[T]): Option[T] =
    get(index).flatMap {
      case BSONNull => Option.empty[T]
      case value => reader.readOpt(value)
    }

  /**
   * Gets the [[BSONValue]] at the given `index`,
   * and converts it with the given implicit [[BSONReader]].
   *
   * If there is no matching value, or the value could not be deserialized,
   * or converted, returns a `Failure`.
   *
   * The `Failure` holds a [[exceptions.BSONValueNotFoundException]]
   * if the index could not be found.
   *
   * {{{
   * import reactivemongo.api.bson.BSONArray
   *
   * val arr = BSONArray(1, "foo")
   *
   * arr.getAsTry[Int](0) // Success(1)
   * arr.getAsTry[Int](1) // Failure(BSONValueNotFoundException(..))
   * }}}
   *
   * @param index $indexParam
   */
  def getAsTry[T](index: Int)(implicit reader: BSONReader[T]): Try[T] =
    get(index) match {
      case None | Some(BSONNull) =>
        Failure(BSONValueNotFoundException(index, this))

      case Some(v) => reader.readTry(v)
    }

  /**
   * Returns the [[BSONValue]] at the given `index`,
   * and converts it with the given implicit [[BSONReader]].
   *
   * If there is no matching value, or the value could not be deserialized,
   * or converted, returns the `default` value.
   *
   * {{{
   * import reactivemongo.api.bson.BSONArray
   *
   * val arr = BSONArray(1, "foo")
   *
   * arr.getOrElse[Int](0, -1) // 1
   * arr.getOrElse[Int](1, -1) // -1; "foo" not int
   * }}}
   *
   * @param index $indexParam
   */
  def getOrElse[T](index: Int, default: => T)(
    implicit
    reader: BSONReader[T]): T = get(index) match {
    case Some(BSONNull) => default
    case Some(value) => reader.readOrElse(value, default)
    case _ => default
  }

  /**
   * Gets the [[BSONValue]] at the given `index`,
   * and converts it with the given implicit [[BSONReader]].
   *
   * If there is no matching value, `Success(None)` is returned.
   * If there is a value, it must be valid or a `Failure` is returned.
   *
   * {{{
   * import reactivemongo.api.bson.BSONArray
   *
   * val arr = BSONArray(1, "foo")
   *
   * arr.getAsUnflattenedTry[Int](0) // Success(Some(1))
   * arr.getAsUnflattenedTry[Int](1) // Failure(BSONValueNotFoundException(..))
   * arr.getAsUnflattenedTry[Int](2) // Success(None) // no value at 2
   * }}}
   *
   * @param index $indexParam
   */
  def getAsUnflattenedTry[T](index: Int)(
    implicit
    reader: BSONReader[T]): Try[Option[T]] = get(index) match {
    case None | Some(BSONNull) =>
      Success(Option.empty[T])

    case Some(v) => reader.readTry(v).map(Some(_))
  }

  private[bson] final def copy(values: IndexedSeq[BSONValue] = this.values): BSONArray = BSONArray(values)

  override def equals(that: Any): Boolean = that match {
    case other: BSONArray => {
      if (values == other.values) true
      else values.sortBy(_.hashCode) == other.values.sortBy(_.hashCode)
    }

    case _ => false
  }

  override def hashCode: Int = values.hashCode

  override def toString: String = s"BSONArray(<${if (isEmpty) "empty" else "non-empty"}>)"

  private[reactivemongo] lazy val byteSize: Int =
    values.zipWithIndex.foldLeft(5) {
      case (sz, (v, i)) =>
        sz + 2 /* '\0' + type code */ + i.toString.getBytes.size + v.byteSize
    }
}

/**
 * [[BSONArray]] utilities
 *
 * {{{
 * import reactivemongo.api.bson.{ BSONArray, BSONString }
 *
 * BSONArray("foo", 1) match {
 *   case BSONArray(BSONString(s) +: _) => s == "foo"
 *   case _ => false
 * }
 * }}}
 *
 * @define factoryDescr Creates a new [[BSONArray]] containing all the `values`
 */
object BSONArray {
  /**
   * Extracts the values sequence if `that`'s a [[BSONArray]].
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONArray, BSONValue }
   *
   * def foo(input: BSONValue): Unit = input match {
   *   case BSONArray(vs) => pretty(vs)
   *   case _ => println("Not a BSON array")
   * }
   *
   * def bar(arr: BSONArray): Unit = arr match {
   *   // with splat pattern
   *   case BSONArray(Seq(requiredFirst, other @ _*)) =>
   *     println(s"first = \\$requiredFirst")
   *     pretty(other)
   *
   *   case _ =>
   *     println("BSON array doesn't match")
   * }
   *
   * def pretty(values: Seq[BSONValue]): Unit =
   *   println(values.map(BSONValue.pretty).mkString(", "))
   *
   * }}}
   */
  @inline def unapply(that: Any): Option[IndexedSeq[BSONValue]] = that match {
    case array: BSONArray => Some(array.values)
    case _ => None
  }

  /**
   * $factoryDescr.
   *
   * {{{
   * reactivemongo.api.bson.BSONArray("foo", 1L)
   * // [ 'foo', NumberLong(1) ]
   * }}}
   */
  def apply(values: Producer[BSONValue]*): BSONArray = {
    def init = {
      val vs = IndexedSeq.newBuilder[BSONValue]

      values.foreach {
        vs ++= _.generate()
      }

      vs.result()
    }

    new BSONArray {
      lazy val values = init
    }
  }

  /**
   * $factoryDescr in the given sequence.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONArray, BSONLong, BSONString }
   *
   * BSONArray(Seq(BSONString("foo"), BSONLong(1L)))
   * // [ 'foo', NumberLong(1) ]
   * }}}
   */
  def apply(values: IndexedSeq[BSONValue]): BSONArray = {
    def init = values

    new BSONArray {
      val values = init
    }
  }

  /**
   * $factoryDescr in the given `Iterable`.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONArray, BSONLong, BSONString }
   *
   * BSONArray(List(BSONString("foo"), BSONLong(1L)))
   * // [ 'foo', NumberLong(1) ]
   * }}}
   */
  def apply(values: BaseColl[BSONValue]): BSONArray = {
    def init = values.toIndexedSeq

    new BSONArray {
      lazy val values = init
    }
  }

  /**
   * Returns a String representing the given [[BSONArray]].
   *
   * {{{
   * import reactivemongo.api.bson.BSONArray
   *
   * BSONArray pretty BSONArray("foo", 1L)
   * // "[ 'foo', NumberLong(1) ]"
   * }}}
   */
  def pretty(array: BSONArray): String =
    s"[\n${BSONIterator.pretty(0, array.values)}\n]"

  /**
   * An empty BSONArray.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONArray, BSONString }
   *
   * val initial = BSONArray.empty // []
   *
   * initial ++ BSONString("lorem") // [ 'lorem' ]
   * }}}
   */
  val empty: BSONArray = BSONArray(IndexedSeq.empty[BSONValue])
}

/**
 * A BSON binary value.
 *
 * {{{
 * import reactivemongo.api.bson.{ BSONBinary, Subtype }
 *
 * BSONBinary("foo".getBytes("UTF-8"), Subtype.GenericBinarySubtype)
 * }}}
 *
 * @param value The binary content.
 * @param subtype The type of the binary content.
 */
final class BSONBinary private[bson] (
  private[bson] val value: ReadableBuffer,
  val subtype: Subtype) extends BSONValue {

  val code = 0x05
  val byteCode = 0x05: Byte

  /** Returns the whole binary content as array. */
  def byteArray: Array[Byte] = value.duplicate().readArray(value.size)

  override private[reactivemongo] lazy val byteSize = {
    5 /* header = 4 (value.readable: Int) + 1 (subtype.value.toByte) */ +
      value.readable()
  }

  override lazy val hashCode: Int = {
    import scala.util.hashing.MurmurHash3

    val nh = MurmurHash3.mix(MurmurHash3.productSeed, subtype.##)

    MurmurHash3.mixLast(nh, value.hashCode)
  }

  override def equals(that: Any): Boolean = that match {
    case other: BSONBinary =>
      (subtype == other.subtype &&
        (value == other.value ||
          // compare content ignoring indexes
          value.duplicate().equals(other.value.duplicate())))

    case _ => false
  }

  override lazy val toString: String =
    s"BSONBinary(${subtype}, size = ${value.readable()})"
}

/**
 * [[BSONBinary]] utilities
 *
 * {{{
 * import reactivemongo.api.bson.{ BSONBinary, Subtype }
 *
 * val bin1 = BSONBinary(
 *   "foo".getBytes("UTF-8"), Subtype.GenericBinarySubtype)
 *
 * // See Subtype.UuidSubtype
 * val uuid = BSONBinary(java.util.UUID.randomUUID())
 * }}}
 */
object BSONBinary {
  /** Extracts the [[Subtype]] if `that`'s a [[BSONBinary]]. */
  def unapply(binary: BSONBinary): Option[Subtype] =
    Some(binary).map(_.subtype)

  /**
   * Creates a [[BSONBinary]] with given `value` and [[Subtype]].
   *
   * {{{
   * import reactivemongo.api.bson.Subtype
   *
   * reactivemongo.api.bson.BSONBinary(
   *   "foo".getBytes("UTF-8"), Subtype.GenericBinarySubtype)
   * }}}
   */
  def apply(value: Array[Byte], subtype: Subtype): BSONBinary =
    new BSONBinary(ReadableBuffer(value), subtype)

  /**
   * Creates a [[BSONBinary]] from the given UUID
   * (with [[Subtype.UuidSubtype]]).
   *
   * {{{
   * reactivemongo.api.bson.BSONBinary(java.util.UUID.randomUUID())
   * }}}
   */
  def apply(id: java.util.UUID): BSONBinary = {
    val bytes = Array.ofDim[Byte](16)
    val buf = ByteBuffer.wrap(bytes)

    buf putLong id.getMostSignificantBits
    buf putLong id.getLeastSignificantBits

    BSONBinary(bytes, Subtype.UuidSubtype)
  }

  /** Returns a string representation of the given [[BSONBinary]]. */
  def pretty(bin: BSONBinary): String = {
    val b64 = java.util.Base64.getEncoder.encodeToString(bin.byteArray)

    s"BinData(${bin.subtype.value}, '${b64}')"
  }
}

/** BSON undefined value */
sealed trait BSONUndefined extends BSONValue with BSONBooleanLike.Value {
  val code = 0x06
  val byteCode = (0x06: Byte)

  override private[reactivemongo] val byteSize = 0

  override val toBoolean = Success(false)

  override def toString: String = "BSONUndefined"
}

/** Single value for [[BSONUndefined]] type */
object BSONUndefined extends BSONUndefined {
  val pretty = "undefined"
}

/**
 * [[https://docs.mongodb.com/manual/reference/bson-types/#objectid BSON ObjectId]] value.
 *
 * {{{
 * import scala.util.Try
 * import reactivemongo.api.bson.BSONObjectID
 *
 * val oid: BSONObjectID = BSONObjectID.generate()
 *
 * def foo: Try[BSONObjectID] = BSONObjectID.parse(oid.stringify)
 * }}}
 *
 * | Timestamp (seconds) | Machine identifier | Thread identifier | Increment
 * | ---                 | ---                | ---               | ---
 * | 4 bytes             | 3 bytes            | 2 bytes           | 3 bytes
 */
sealed abstract class BSONObjectID extends BSONValue {
  import java.util.Arrays

  val code = 0x07
  val byteCode = 0x07: Byte

  protected val raw: Array[Byte]

  /** The time of this BSONObjectId, in seconds */
  def timeSecond: Int

  /** The time of this BSONObjectId, in milliseconds */
  @inline final def time: Long = timeSecond * 1000L

  @inline override private[reactivemongo] val byteSize = 12

  /** Returns the whole binary content as array. */
  final def byteArray: Array[Byte] = Arrays.copyOf(raw, byteSize)

  /**
   * The hexadecimal String representation.
   *
   * {{{
   * import reactivemongo.api.bson.BSONObjectID
   *
   * def foo(oid: BSONObjectID): scala.util.Try[BSONObjectID] = {
   *   val repr: String = oid.stringify
   *   BSONObjectID.parse(repr)
   * }
   * }}}
   */
  lazy val stringify = Digest.hex2Str(raw)

  override def toString = s"BSONObjectID($stringify)"

  override def equals(that: Any): Boolean = that match {
    case BSONObjectID(other) =>
      Arrays.equals(raw, other)

    case _ => false
  }

  override lazy val hashCode: Int = Arrays.hashCode(raw)
}

/** [[BSONObjectID]] utilities */
object BSONObjectID {
  private val maxCounterValue = 16777216
  private val increment = new java.util.concurrent.atomic.AtomicInteger(
    scala.util.Random nextInt maxCounterValue)

  private def counter: Int = (increment.getAndIncrement + maxCounterValue) % maxCounterValue

  /**
   * The following implementation of machineId works around
   * openjdk limitations in versions 6 and 7.
   *
   * Openjdk fails to parse /proc/net/if_inet6 correctly to determine macaddress
   * resulting in SocketException thrown.
   *
   * Please see:
   *
   * - https://github.com/openjdk-mirror/jdk7u-jdk/blob/feeaec0647609a1e6266f902de426f1201f77c55/src/solaris/native/java/net/NetworkInterface.c#L1130
   * - http://lxr.free-electrons.com/source/net/ipv6/addrconf.c?v=3.11#L3442
   * - http://lxr.free-electrons.com/source/include/linux/netdevice.h?v=3.11#L1130
   * - http://bugs.sun.com/bugdatabase/view_bug.do?bug_id=7078386
   *
   * and fix in openjdk8:
   * - http://hg.openjdk.java.net/jdk8/tl/jdk/rev/b1814b3ea6d3
   */
  private val machineId: Array[Byte] = {
    import java.net.{ InetAddress, NetworkInterface, NetPermission }

    def p(n: String) = System.getProperty(n)

    val validPlatform = try {
      val correctVersion = p("java.version").substring(0, 3).toFloat >= 1.8
      val noIpv6 = p("java.net.preferIPv4Stack").toBoolean == true
      val isLinux = p("os.name") == "Linux"

      !isLinux || correctVersion || noIpv6
    } catch {
      case NonFatal(_) => false
    }

    // Check java policies
    val permitted = try {
      System.getSecurityManager().
        checkPermission(new NetPermission("getNetworkInformation"))

      true
    } catch {
      case NonFatal(_) => false
    }

    if (validPlatform && permitted) {
      @annotation.tailrec
      def resolveAddr(ifaces: java.util.Enumeration[NetworkInterface]): Array[Byte] = {
        if (ifaces.hasMoreElements) {
          val hwAddr: Array[Byte] = try {
            ifaces.nextElement().getHardwareAddress()
          } catch {
            case NonFatal(_) => null
          }

          if (hwAddr != null && hwAddr.length == 6) {
            hwAddr
          } else {
            resolveAddr(ifaces)
          }
        } else {
          // Fallback:
          InetAddress.getLocalHost.getHostName.getBytes("UTF-8")
        }
      }

      val ha = resolveAddr(NetworkInterface.getNetworkInterfaces())

      Digest.md5(ha).take(3)
    } else {
      val threadId = Thread.currentThread.getId.toInt

      Array[Byte](
        (threadId & 0xFF).toByte,
        (threadId >> 8 & 0xFF).toByte,
        (threadId >> 16 & 0xFF).toByte)

    }
  }

  /** Extracts the bytes if `that`'s a [[BSONObjectID]]. */
  def unapply(that: Any): Option[Array[Byte]] = that match {
    case id: BSONObjectID => Some(id.byteArray)
    case _ => None
  }

  /** Tries to make a BSON ObjectId from a hexadecimal string representation. */
  def parse(id: String): Try[BSONObjectID] = {
    if (id.length != 24) Failure[BSONObjectID](
      new IllegalArgumentException(s"Wrong ObjectId (length != 24): '$id'"))
    else try {
      parse(Digest str2Hex id)
    } catch {
      case NonFatal(cause) =>
        Failure(new IllegalArgumentException(s"Wrong ObjectId (not a valid hex string): '$id'", cause))
    }
  }

  /** Tries to make a BSON ObjectId from a binary representation. */
  def parse(bytes: Array[Byte]): Try[BSONObjectID] = {
    if (bytes.length != 12) {
      Failure[BSONObjectID](new IllegalArgumentException(
        s"Wrong ObjectId: length(${bytes.length}) != 12"))

    } else Try {
      (bytes(0) << 24) | ((bytes(1) & 0xFF) << 16) | (
        (bytes(2) & 0xFF) << 8) | (bytes(3) & 0xFF)

    }.map { timeSec =>
      new BSONObjectID {
        val timeSecond = timeSec
        val raw = bytes
      }
    }
  }

  /**
   * Generates a new BSON ObjectID using the current time as seed.
   *
   * @see [[fromTime]]
   */
  def generate(): BSONObjectID = fromTime(System.currentTimeMillis, false)

  /**
   * Generates a new BSON ObjectID from the given timestamp in milliseconds.
   *
   * The included timestamp is the number of seconds since epoch,
   * so a [[BSONObjectID]] time part has only a precision up to the second.
   *
   * To get a reasonably unique ID, you _must_ set `onlyTimestamp` to false.
   *
   * Crafting an ID from a timestamp with `fillOnlyTimestamp` set to true
   * is helpful for range queries; e.g if you want of find documents an `_id`
   * field which timestamp part is greater than or lesser
   * than the one of another id.
   *
   * If you do not intend to use the produced [[BSONObjectID]]
   * for range queries, then you'd rather use the `generate` method instead.
   *
   * @param fillOnlyTimestamp if true, the returned BSONObjectID
   * will only have the timestamp bytes set; the other will be set to zero.
   */
  def fromTime(timeMillis: Long, fillOnlyTimestamp: Boolean = false): BSONObjectID = {
    // n of seconds since epoch. Big endian
    val timestamp = (timeMillis / 1000L).toInt
    val id = Array.ofDim[Byte](12)

    id(0) = (timestamp >> 24).toByte
    id(1) = (timestamp >> 16).toByte
    id(2) = (timestamp >> 8).toByte
    id(3) = timestamp.toByte

    if (!fillOnlyTimestamp) {
      // machine id, 3 first bytes of md5(macadress or hostname)
      id(4) = machineId(0)
      id(5) = machineId(1)
      id(6) = machineId(2)

      // 2 bytes of the pid or thread id. Thread id in our case. Low endian
      val threadId = Thread.currentThread.getId.toInt
      id(7) = (threadId & 0xFF).toByte
      id(8) = (threadId >> 8 & 0xFF).toByte

      // 3 bytes of counter sequence, which start is randomized. Big endian
      val c = counter
      id(9) = (c >> 16 & 0xFF).toByte
      id(10) = (c >> 8 & 0xFF).toByte
      id(11) = (c & 0xFF).toByte
    }

    new BSONObjectID {
      val raw = id
      val timeSecond = timestamp
    }
  }

  /** Returns the string representation for the given [[BSONObjectID]]. */
  @inline def pretty(oid: BSONObjectID): String =
    s"ObjectId('${oid.stringify}')"
}

/** BSON boolean value */
final class BSONBoolean private[bson] (val value: Boolean)
  extends BSONValue with BSONBooleanLike.Value {

  val code = 0x08
  val byteCode = 0x08: Byte

  override private[reactivemongo] val byteSize = 1

  override lazy val asBoolean: Try[Boolean] = Success(value)

  @inline def toBoolean = asBoolean

  override def hashCode: Int = value.hashCode

  override def equals(that: Any): Boolean = that match {
    case other: BSONBoolean => value == other.value
    case _ => false
  }

  override def toString: String = s"BSONBoolean($value)"
}

object BSONBoolean {
  /** Extracts the boolean value if `that`'s a [[BSONBoolean]]. */
  def unapply(that: Any): Option[Boolean] = that match {
    case bson: BSONBoolean => Some(bson.value)
    case _ => None
  }

  /** Returns a [[BSONBoolean]] */
  @inline def apply(value: Boolean): BSONBoolean = new BSONBoolean(value)
}

/** BSON date time value */
final class BSONDateTime private[bson] (val value: Long)
  extends BSONValue with BSONNumberLike.Value {
  val code = 0x09
  val byteCode = 0x09: Byte

  override private[bson] lazy val asDecimal: Try[BigDecimal] =
    Success(BigDecimal(value))

  @inline override def toDouble: Try[Double] = super.toDouble

  @inline override def toFloat: Try[Float] = super.toFloat

  private[bson] override lazy val asLong: Try[Long] = Success(value)

  @inline def toLong = asLong

  @inline def toInt = Try(value.toInt)

  override private[bson] lazy val asDateTime: Try[Instant] =
    Success(Instant ofEpochMilli value)

  override private[reactivemongo] val byteSize = 8

  override def hashCode: Int = value.hashCode

  override def equals(that: Any): Boolean = that match {
    case other: BSONDateTime => value == other.value
    case _ => false
  }

  override def toString: String = s"BSONDateTime($value)"
}

object BSONDateTime {
  /** Extracts the dateTime value if `that`'s a [[BSONDateTime]]. */
  def unapply(that: Any): Option[Long] = that match {
    case bson: BSONDateTime => Some(bson.value)
    case _ => None
  }

  /**
   * Returns a [[BSONDateTime]].
   *
   * @param value the date/time value
   */
  @inline def apply(value: Long): BSONDateTime = new BSONDateTime(value)

  /** Returns a string representation for the given [[BSONDateTime]]. */
  @inline def pretty(time: BSONDateTime): String =
    s"ISODate('${java.time.Instant ofEpochMilli time.value}')"
}

/** BSON null value */
sealed trait BSONNull extends BSONValue with BSONBooleanLike.Value {
  val code = 0x0A
  val byteCode = 0x0A: Byte

  override private[reactivemongo] val byteSize = 0

  override val toBoolean = Success(false)

  override def toString: String = "BSONNull"
}

object BSONNull extends BSONNull {
  val pretty = "null"
}

/**
 * BSON Regex value.
 *
 * @param value the regex value (expression)
 * @param flags the regex flags
 */
final class BSONRegex private[bson] (
  val value: String, val flags: String)
  extends BSONValue {
  val code = 0x0B
  val byteCode = 0x0B: Byte

  override private[reactivemongo] lazy val byteSize =
    2 + value.getBytes.size + flags.getBytes.size

  override lazy val hashCode: Int = {
    import scala.util.hashing.MurmurHash3

    val nh = MurmurHash3.mix(MurmurHash3.productSeed, value.##)

    MurmurHash3.mixLast(nh, flags.hashCode)
  }

  override def equals(that: Any): Boolean = that match {
    case other: BSONRegex =>
      (value == other.value && flags == other.flags)

    case _ => false
  }

  override def toString: String = s"BSONRegex($value, $flags)"
}

object BSONRegex {
  /** Extracts the regex value and flags if `that`'s a [[BSONRegex]]. */
  def unapply(that: Any): Option[(String, String)] = that match {
    case regex: BSONRegex => Some(regex.value -> regex.flags)
    case _ => None
  }

  /** Returns a [[BSONRegex]] */
  @inline def apply(value: String, flags: String): BSONRegex =
    new BSONRegex(value, flags)
}

/**
 * BSON JavaScript value.
 *
 * @param value The JavaScript source code.
 */
final class BSONJavaScript private[bson] (val value: String) extends BSONValue {
  val code = 0x0D
  val byteCode = 0x0D: Byte

  override private[reactivemongo] lazy val byteSize = 5 + value.getBytes.size

  override private[reactivemongo] lazy val asString: Try[String] =
    Success(value)

  override def hashCode: Int = value.hashCode

  override def equals(that: Any): Boolean = that match {
    case other: BSONJavaScript => value == other.value
    case _ => false
  }

  override def toString: String = s"BSONJavaScript($value)"
}

object BSONJavaScript {
  /** Extracts the javaScript value if `that`'s a [[BSONJavaScript]]. */
  def unapply(that: Any): Option[String] = that match {
    case bson: BSONJavaScript => Some(bson.value)
    case _ => None
  }

  /** Returns a [[BSONJavaScript]] */
  @inline def apply(value: String): BSONJavaScript = new BSONJavaScript(value)
}

/**
 * BSON Symbol value.
 *
 * @param value the symbol value (name)
 */
final class BSONSymbol private[bson] (val value: String) extends BSONValue {
  val code = 0x0E
  val byteCode = 0x0E: Byte

  override private[reactivemongo] lazy val byteSize = 5 + value.getBytes.size

  override private[reactivemongo] lazy val asString: Try[String] =
    Success(value)

  override def hashCode: Int = value.hashCode

  override def equals(that: Any): Boolean = that match {
    case other: BSONSymbol => value == other.value
    case _ => false
  }

  override def toString: String = s"BSONSymbol($value)"
}

object BSONSymbol {
  /** Extracts the symbol value if `that`'s a [[BSONSymbol]]. */
  def unapply(that: Any): Option[String] = that match {
    case sym: BSONSymbol => Some(sym.value)
    case _ => None
  }

  /** Returns a [[BSONSymbol]] */
  @inline def apply(value: String): BSONSymbol = new BSONSymbol(value)
}

/**
 * BSON JavaScript value with scope (WS).
 *
 * @param value The JavaScript source code.
 */
final class BSONJavaScriptWS private[bson] (
  val value: String,
  val scope: BSONDocument) extends BSONValue {
  val code = 0x0F
  val byteCode = 0x0F: Byte

  override private[reactivemongo] lazy val byteSize =
    5 + value.getBytes.size + scope.byteSize

  override private[reactivemongo] lazy val asString: Try[String] =
    Success(value)

  override def hashCode: Int = {
    import scala.util.hashing.MurmurHash3

    val nh = MurmurHash3.mix(MurmurHash3.productSeed, value.hashCode)

    MurmurHash3.mixLast(nh, scope.hashCode)
  }

  override def equals(that: Any): Boolean = that match {
    case other: BSONJavaScriptWS =>
      (value == other.value) && (scope == other.scope)

    case _ => false
  }

  override def toString: String = s"BSONJavaScriptWS($value)"
}

object BSONJavaScriptWS {
  /** Extracts the javaScriptWS value if `that`'s a [[BSONJavaScriptWS]]. */
  def unapply(that: Any): Option[(String, BSONDocument)] = that match {
    case js: BSONJavaScriptWS => Some(js.value -> js.scope)
    case _ => None
  }

  /** Returns a [[BSONJavaScriptWS]] with given `scope` */
  @inline def apply(value: String, scope: BSONDocument): BSONJavaScriptWS =
    new BSONJavaScriptWS(value, scope)

}

/** BSON Integer value */
final class BSONInteger private[bson] (val value: Int)
  extends BSONValue with BSONNumberLike.Value with BSONBooleanLike.Value {

  val code = 0x10
  val byteCode = 0x10: Byte

  override private[reactivemongo] val byteSize = 4

  override lazy val toDouble: Try[Double] = Success(value.toDouble)

  override lazy val toFloat: Try[Float] = {
    if (value >= Float.MinValue && value <= Float.MaxValue) {
      Try(value.toFloat)
    } else {
      super.toFloat
    }
  }

  override lazy val asInt: Try[Int] = Success(value)
  @inline def toInt = asInt

  private[bson] override lazy val asLong: Try[Long] = Success(value.toLong)
  @inline def toLong = asLong

  override private[bson] lazy val asDecimal: Try[BigDecimal] =
    Success(BigDecimal(value))

  @inline def toBoolean = Success(value != 0)

  override def hashCode: Int = value.hashCode

  override def equals(that: Any): Boolean = that match {
    case other: BSONInteger => value == other.value
    case _ => false
  }

  override def toString: String = s"BSONInteger($value)"
}

object BSONInteger {
  /** Extracts the integer value if `that`'s a [[BSONInteger]]. */
  def unapply(that: Any): Option[Int] = that match {
    case i: BSONInteger => Some(i.value)
    case _ => None
  }

  /** Returns a [[BSONInteger]] */
  @inline def apply(value: Int): BSONInteger = new BSONInteger(value)
}

/**
 * [[https://docs.mongodb.com/manual/reference/bson-types/#timestamps BSON Timestamp]] value
 *
 * @param value
 */
sealed abstract class BSONTimestamp
  extends BSONValue with BSONNumberLike.Value {

  val code = 0x11
  val byteCode = 0x11: Byte

  /**
   * Raw value (most significant 32 bits = epoch seconds / least significant 32 bits = ordinal)
   */
  def value: Long

  /** Seconds since the Unix epoch */
  def time: Int

  /** Ordinal (with the second) */
  def ordinal: Int

  @inline override def toDouble: Try[Double] = super.toDouble

  @inline override def toFloat: Try[Float] = super.toFloat

  private[bson] override lazy val asLong: Try[Long] = Success(value)
  @inline def toLong = asLong

  @inline def toInt: Try[Int] = Failure(
    TypeDoesNotMatchException("BSONInteger", "BSONTimestamp"))

  override private[bson] lazy val asDateTime: Try[Instant] =
    Success(Instant ofEpochMilli value)

  override private[reactivemongo] val byteSize = 8

  @inline private[bson] def underlying: BSONValue = this

  override def hashCode: Int = (value ^ (value >>> 32)).toInt

  override def equals(that: Any): Boolean = that match {
    case other: BSONTimestamp => value == other.value
    case _ => false
  }

  override def toString: String = s"BSONTimestamp($value)"
}

/** Timestamp companion */
object BSONTimestamp {
  /** Extracts the timestamp value if `that`'s a [[BSONTimestamp]]. */
  def unapply(that: Any): Option[Long] = that match {
    case ts: BSONTimestamp => Some(ts.value)
    case _ => None
  }

  /** Returns a [[BSONTimestamp]] */
  @inline def apply(value: Long): BSONTimestamp = {
    @inline def v = value

    new BSONTimestamp {
      val value = v
      val time = (value >> 32).toInt
      val ordinal = value.toInt
    }
  }

  /**
   * Returns the timestamp corresponding to the given `time` and `ordinal`.
   *
   * @param time the 32bits time value (seconds since the Unix epoch)
   * @param ordinal an incrementing ordinal for operations within a same second
   */
  def apply(time: Int, ordinal: Int): BSONTimestamp = {
    @inline def t = time
    @inline def i = ordinal

    new BSONTimestamp {
      val value = (t.toLong << 32) | (i & 0xFFFFFFFFL)
      val time = t
      val ordinal = i
    }
  }

  /** Returns the string representation for the given [[BSONTimestamp]]. */
  @inline def pretty(ts: BSONTimestamp): String =
    s"Timestamp(${ts.time}, ${ts.ordinal})"
}

/** BSON Long value */
final class BSONLong private[bson] (val value: Long)
  extends BSONValue with BSONNumberLike.Value with BSONBooleanLike.Value {

  val code = 0x12
  val byteCode = 0x12: Byte

  override lazy val toDouble: Try[Double] = {
    if (value >= Double.MinValue && value <= Double.MaxValue) {
      Try(value.toDouble)
    } else super.toDouble
  }

  override lazy val toFloat: Try[Float] = {
    if (value >= Float.MinValue && value <= Float.MaxValue) {
      Try(value.toFloat)
    } else super.toFloat
  }

  override lazy val asInt: Try[Int] = {
    if (value >= Int.MinValue && value <= Int.MaxValue) Try(value.toInt)
    else super.asInt
  }

  @inline def toInt = Try(value.toInt)

  private[bson] override lazy val asLong: Try[Long] = Success(value)

  @inline def toLong = asLong

  override private[bson] lazy val asDecimal: Try[BigDecimal] =
    Success(BigDecimal(value))

  @inline def toBoolean = Success(value != 0L)

  override private[reactivemongo] val byteSize = 8

  override def hashCode: Int = value.hashCode

  override def equals(that: Any): Boolean = that match {
    case other: BSONLong => value == other.value
    case _ => false
  }

  override def toString: String = s"BSONLong($value)"
}

object BSONLong {
  /** Extracts the value if `that`'s a [[BSONLong]]. */
  def unapply(that: Any): Option[Long] = that match {
    case bson: BSONLong =>
      Some(bson.value)

    case _ => None
  }

  /** Returns a [[BSONLong]] */
  @inline def apply(value: Long): BSONLong = new BSONLong(value)

  /** Returns the string representation for the given [[BSONLong]]. */
  @inline def pretty(long: BSONLong): String = s"NumberLong(${long.value})"
}

/**
 * Value wrapper for a [[https://github.com/mongodb/specifications/blob/master/source/bson-decimal128/decimal128.rst BSON 128-bit decimal]].
 *
 * @param high the high-order 64 bits
 * @param low the low-order 64 bits
 */
final class BSONDecimal private[bson] (
  val high: Long,
  val low: Long) extends BSONValue
  with BSONNumberLike.Value with BSONBooleanLike.Value {

  val code = 0x13
  val byteCode = 0x13: Byte

  override private[bson] lazy val asDecimal: Try[BigDecimal] =
    BSONDecimal.toBigDecimal(this)

  override lazy val toDouble: Try[Double] =
    asDecimal.filter(_.isDecimalDouble).map(_.toDouble)

  override lazy val toFloat: Try[Float] =
    asDecimal.filter(_.isDecimalDouble).map(_.toFloat)

  override lazy val asInt: Try[Int] =
    asDecimal.filter(_.isValidInt).map(_.toInt)

  @inline def toInt = asInt

  private[bson] override lazy val asLong: Try[Long] =
    asDecimal.filter(_.isValidLong).map(_.toLong)

  @inline def toLong = asLong

  @inline def toBoolean: Try[Boolean] = toInt.map(_ != 0)

  /** Returns true if is negative. */
  lazy val isNegative: Boolean =
    (high & Decimal128.SignBitMask) == Decimal128.SignBitMask

  /** Returns true if is infinite. */
  lazy val isInfinite: Boolean =
    (high & Decimal128.InfMask) == Decimal128.InfMask

  /** Returns true if is Not-A-Number (NaN). */
  lazy val isNaN: Boolean =
    (high & Decimal128.NaNMask) == Decimal128.NaNMask

  override private[reactivemongo] lazy val byteSize = 16

  @inline private[bson] def underlying: BSONValue = this

  // ---

  /**
   * Returns the [[https://github.com/mongodb/specifications/blob/master/source/bson-decimal128/decimal128.rst#to-string-representation string representation]].
   */
  override def toString: String = Decimal128.toString(this)

  override def equals(that: Any): Boolean = that match {
    case BSONDecimal(h, l) => (high == h) && (low == l)
    case _ => false
  }

  override lazy val hashCode: Int = {
    val result = (low ^ (low >>> 32)).toInt

    31 * result + (high ^ (high >>> 32)).toInt
  }
}

object BSONDecimal {
  import java.math.MathContext

  /**
   * Factory alias.
   *
   * @param high the high-order 64 bits
   * @param low the low-order 64 bits
   */
  @inline def apply(high: Long, low: Long): BSONDecimal =
    new BSONDecimal(high, low)

  /**
   * Returns a BSON decimal (Decimal128) corresponding to the given BigDecimal.
   *
   * @param value the BigDecimal representation
   */
  @inline def fromBigDecimal(value: JBigDec): Try[BSONDecimal] =
    Decimal128.fromBigDecimal(value, value.signum == -1)

  /**
   * Returns a BSON decimal (Decimal128) corresponding to the given BigDecimal.
   *
   * @param value the BigDecimal representation
   */
  @inline def fromBigDecimal(value: BigDecimal): Try[BSONDecimal] =
    Decimal128.fromBigDecimal(value.bigDecimal, value.signum == -1)

  /**
   * Returns a Decimal128 value represented the given high 64bits value,
   * using a default for the low one.
   *
   * @param high the high-order 64 bits
   */
  @inline def fromLong(high: Long): Try[BSONDecimal] =
    fromBigDecimal(new JBigDec(high, MathContext.DECIMAL128))

  /**
   * Returns the Decimal128 corresponding to the given string representation.
   *
   * @param repr the Decimal128 value represented as string
   * @see [[https://github.com/mongodb/specifications/blob/master/source/bson-decimal128/decimal128.rst#from-string-representation Decimal128 string representation]]
   */
  def parse(repr: String): Try[BSONDecimal] = Decimal128.parse(repr)

  /** Returns the corresponding BigDecimal. */
  def toBigDecimal(decimal: BSONDecimal): Try[BigDecimal] =
    Decimal128.toBigDecimal(decimal).map(BigDecimal(_))

  /** Extracts the (high, low) representation if `that`'s [[BSONDecimal]]. */
  def unapply(that: Any): Option[(Long, Long)] = that match {
    case decimal: BSONDecimal => Some(decimal.high -> decimal.low)
    case _ => None
  }

  /** Returns the string representation for the given [[BSONDecimal]]. */
  def pretty(dec: BSONDecimal): String = dec.asDecimal match {
    case Success(v) => s"NumberDecimal($v)"
    case _ => "NumberDecimal(NaN)"
  }

  // ---

  /**
   * Decimal128 representation of the positive infinity
   */
  val PositiveInf: BSONDecimal = BSONDecimal(Decimal128.InfMask, 0)

  /**
   * Decimal128 representation of the negative infinity
   */
  val NegativeInf: BSONDecimal =
    BSONDecimal(Decimal128.InfMask | Decimal128.SignBitMask, 0)

  /**
   * Decimal128 representation of a negative Not-a-Number (-NaN) value
   */
  val NegativeNaN: BSONDecimal =
    BSONDecimal(Decimal128.NaNMask | Decimal128.SignBitMask, 0)

  /**
   * Decimal128 representation of a Not-a-Number (NaN) value
   */
  val NaN: BSONDecimal = BSONDecimal(Decimal128.NaNMask, 0)

  /**
   * Decimal128 representation of a postive zero value
   */
  val PositiveZero: BSONDecimal =
    BSONDecimal(0x3040000000000000L, 0x0000000000000000L)

  /**
   * Decimal128 representation of a negative zero value
   */
  val NegativeZero: BSONDecimal =
    BSONDecimal(0xb040000000000000L, 0x0000000000000000L)
}

/** BSON Min key value */
sealed trait BSONMinKey extends BSONValue {
  val byteCode = 0xFF.toByte
  val code = byteCode.toInt

  override private[reactivemongo] val byteSize = 0
  override val toString = "BSONMinKey"
}

object BSONMinKey extends BSONMinKey {
  val pretty = "MinKey"
}

/** BSON Max key value */
sealed trait BSONMaxKey extends BSONValue {
  val code = 0x7F
  val byteCode = 0x7F: Byte

  override private[reactivemongo] val byteSize = 0
  override val toString = "BSONMaxKey"
}

object BSONMaxKey extends BSONMaxKey {
  val pretty = "MaxKey"
}

/**
 * A `BSONDocument` structure (BSON type `0x03`).
 *
 * A `BSONDocument` is an unordered set of fields `(String, BSONValue)`.
 *
 *
 * '''Note:''' The insertion/initial order of the fields may not
 * be maintained through the operations.
 *
 * @define keyParam the key to be found in the document
 */
sealed abstract class BSONDocument
  extends BSONValue with ElementProducer
  with BSONDocumentLowPriority with BSONDocumentExperimental { self =>

  val code = 0x03
  val byteCode = 0x03: Byte

  /** The document fields */
  private[bson] def fields: Map[String, BSONValue]

  /**
   * The document fields as a sequence of [[BSONElement]]s.
   */
  def elements: Seq[BSONElement]

  /**
   * Returns the `Map` representation for this document.
   *
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * BSONDocument("foo" -> 1).toMap
   * // => Map("foo" -> BSONInteger(1))
   * }}}
   */
  @inline final def toMap: Map[String, BSONValue] = fields

  /**
   * Checks whether the given key is found in this element set.
   *
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * val doc = BSONDocument("foo" -> 1)
   *
   * doc.contains("foo") // true
   * doc.contains("bar") // false
   * }}}
   *
   * @param key $keyParam
   * @return true if the key is found
   */
  def contains(key: String): Boolean = fields.contains(key)

  /**
   * Returns the [[BSONValue]] associated with the given `key`.
   * If the key cannot be found, returns `None`.
   *
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * val doc = BSONDocument("foo" -> 1)
   *
   * doc.get("foo") // Some(BSONInteger(1))
   * doc.contains("bar") // None
   * }}}
   *
   * @param key $keyParam
   */
  final def get(key: String): Option[BSONValue] = fields.get(key)

  /**
   * The first/mandatory element, if any.
   *
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * BSONDocument("foo" -> 1).
   *   headOption // Some(BSONInteger(1))
   * }}}
   */
  def headOption: Option[BSONElement]

  /**
   * Returns the values of the document fields.
   *
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * BSONDocument("foo" -> 1).
   *   values // Seq(BSONInteger(1))
   * }}}
   */
  final def values: Iterable[BSONValue] = fields.values

  /**
   * Returns the [[BSONDocument]] containing all the elements
   * of this one and the elements of the given document.
   *
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * val doc1 = BSONDocument("foo" -> 1)
   * val doc2 = BSONDocument("bar" -> "lorem")
   *
   * doc1 ++ doc2 // { 'foo': 1, 'bar': 'lorem' }
   * }}}
   */
  def ++(doc: BSONDocument): BSONDocument = new BSONDocument {
    lazy val fields: Map[String, BSONValue] = self.fields ++ doc.fields

    val elements =
      (toLazy(self.elements) ++ toLazy(doc.elements)).distinct

    @inline def headOption = self.headOption
    val isEmpty = self.isEmpty && doc.isEmpty
  }

  /**
   * Creates a new [[BSONDocument]] containing all the elements
   * of this one and the specified element sequence.
   *
   * {{{
   * import reactivemongo.api.bson.{
   *   BSONDocument, BSONElement, BSONString
   * }
   *
   * val doc = BSONDocument("foo" -> 1)
   *
   * doc ++ BSONElement("bar", BSONString("lorem"))
   * // { 'foo': 1, 'bar': 'lorem' }
   * }}}
   */
  def ++(seq: BSONElement*): BSONDocument = new BSONDocument {
    val elements = (toLazy(self.elements) ++ toLazy(seq)).distinct

    lazy val fields = {
      val m = MMap.empty[String, BSONValue]

      m ++= self.fields

      seq.foreach {
        case BSONElement(name, value) => m.put(name, value)
      }

      m.toMap
    }

    @inline def headOption = self.headOption
    val isEmpty = self.isEmpty && seq.isEmpty
  }

  /**
   * Returns a set without the values corresponding to the specified keys.
   *
   *
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * val doc = BSONDocument("foo" -> 1, "bar" -> "v")
   *
   * doc -- "bar" // { 'foo': 1 }
   * }}}
   */
  def --(keys: String*): BSONDocument = new BSONDocument {
    val fields = self.fields -- keys
    lazy val elements = self.elements.filterNot { e => keys.contains(e.name) }
    @inline def headOption = elements.headOption
    val isEmpty = fields.isEmpty
  }

  /** The number of fields */
  @inline def size: Int = fields.size

  /** Indicates whether this document is empty */
  def isEmpty: Boolean

  /**
   * Returns the [[BSONValue]] associated with the given `key`,
   * and converts it with the given implicit [[BSONReader]].
   *
   * If there is no matching value (or value is a [[BSONNull]]),
   * or the value could not be deserialized, or converted, returns a `None`.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONDocument, BSONNull }
   *
   * val doc = BSONDocument("foo" -> 1, "bar" -> BSONNull)
   *
   * doc.getAsOpt[Int]("foo") // Some(1)
   * doc.getAsOpt[String]("foo") // None, as not a string
   * doc.getAsOpt[Int]("lorem") // None, no 'lorem' key
   * doc.getAsOpt[Int]("bar") // None, as `BSONNull`
   * }}}
   *
   * @param key $keyParam
   *
   * @note When implementing a [[http://reactivemongo.org/releases/latest/documentation/bson/typeclasses.html custom reader]], [[getAsTry]] must be preferred.
   */
  final def getAsOpt[T](key: String)(implicit reader: BSONReader[T]): Option[T] = get(key).flatMap {
    case BSONNull => Option.empty[T]
    case v => reader.readOpt(v)
  }

  /**
   * Gets the [[BSONValue]] associated with the given `key`,
   * and converts it with the given implicit [[BSONReader]].
   *
   * If there is no matching value, or the value could not be deserialized,
   * or converted, returns a `Failure`.
   *
   * The `Failure` may hold a [[exceptions.BSONValueNotFoundException]],
   * if the key could not be found.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONDocument, BSONNull }
   *
   * val doc = BSONDocument("foo" -> 1, "bar" -> BSONNull)
   *
   * doc.getAsTry[Int]("foo") // Success(1)
   * doc.getAsTry[String]("foo") // Failure(..), as not a string
   *
   * doc.getAsTry[Int]("lorem")
   * // Failure(BSONValueNotFoundException), no 'lorem' key
   *
   * doc.getAsTry[Int]("bar")
   * // Failure(BSONValueNotFoundException), as `BSONNull`
   * }}}
   *
   * @param key $keyParam
   */
  final def getAsTry[T](key: String)(implicit reader: BSONReader[T]): Try[T] =
    get(key) match {
      case None | Some(BSONNull) =>
        Failure(BSONValueNotFoundException(key, this))

      case Some(v) => reader.readTry(v)
    }

  /**
   * Returns the [[BSONValue]] associated with the given `key`,
   * and converts it with the given implicit [[BSONReader]].
   *
   * If there is no matching value (or value is a [[BSONNull]]),
   * or the value could not be deserialized, or converted,
   * returns the `default` value.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONDocument, BSONNull }
   *
   * val doc = BSONDocument("foo" -> 1, "bar" -> BSONNull)
   *
   * doc.getOrElse[Int]("foo", -1) // 1
   * doc.getOrElse[String]("foo", "default") // 'default', as not a string
   * doc.getOrElse[Int]("lorem", -1) // -1, no 'lorem' key
   * doc.getOrElse[Int]("bar", -1) // -1, as `BSONNull`
   * }}}
   *
   * @param key $keyParam
   *
   * @note When implementing a [[http://reactivemongo.org/releases/latest/documentation/bson/typeclasses.html custom reader]], [[getAsTry]] must be preferred.
   */
  final def getOrElse[T](key: String, default: => T)(
    implicit
    reader: BSONReader[T]): T = get(key) match {
    case Some(BSONNull) => default
    case Some(value) => reader.readOrElse(value, default)
    case _ => default
  }

  /**
   * Gets the [[BSONValue]] at the given `key`,
   * and converts it with the given implicit [[BSONReader]].
   *
   * If there is no matching value, `Success(None)` is returned.
   * If there is a value, it must be valid or a `Failure` is returned.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONDocument, BSONNull }
   *
   * val doc = BSONDocument("foo" -> 1, "bar" -> BSONNull)
   *
   * doc.getAsUnflattenedTry[Int]("foo") // Success(Some(1))
   *
   * doc.getAsUnflattenedTry[String]("foo") // Failure(..), as not a string
   *
   * doc.getAsUnflattenedTry[Int]("lorem")
   * // Success(None), no 'lorem' key
   *
   * doc.getAsUnflattenedTry[Int]("bar")
   * // Success(None), as `BSONNull`
   * }}}
   *
   * @param key $keyParam
   */
  final def getAsUnflattenedTry[T](key: String)(
    implicit
    reader: BSONReader[T]): Try[Option[T]] = get(key) match {
    case None | Some(BSONNull) =>
      Success(Option.empty[T])

    case Some(v) => reader.readTry(v).map(Some(_))
  }

  // ---

  private[bson] final def copy(newFields: Map[String, BSONValue] = self.fields): BSONDocument = new BSONDocument {
    val fields = newFields

    val elements = toLazy(newFields).map {
      case (k, v) => BSONElement(k, v)
    }

    @inline def isEmpty = newFields.isEmpty
    @inline def headOption = elements.headOption
  }

  override def equals(that: Any): Boolean = that match {
    case other: BSONDocument => {
      if (fields == other.fields) true
      else fields.toSeq.sortBy(_._1) == other.fields.toSeq.sortBy(_._1)
    }

    case _ => false
  }

  override def hashCode: Int = fields.toSeq.sortBy(_._1).hashCode

  override def toString: String = "BSONDocument(<" + (if (isEmpty) "empty" else "non-empty") + ">)"

  @inline private[bson] final def generate() = elements

  private[reactivemongo] lazy val byteSize: Int = fields.foldLeft(5) {
    case (sz, (n, v)) =>
      sz + 2 /* '\0' + type code */ + n.getBytes.size + v.byteSize
  }
}

/**
 * @define getFieldIf '''EXPERIMENTAL:''' Returns the named element from the current document, if the element is
 */
private[bson] sealed trait BSONDocumentExperimental { self: BSONDocument =>
  /**
   * $getFieldIf an array field.
   */
  def array(name: String): Option[Seq[BSONValue]] =
    get(name).collect {
      case arr: BSONArray => arr.values
    }

  final def values[T](name: String)(implicit r: BSONReader[T]): Option[Seq[T]] = document.getAsOpt[Seq[T]](name)

  /**
   * $getFieldIf a binary field.
   */
  def binary(name: String): Option[Array[Byte]] =
    getAsOpt[Array[Byte]](name)

  /**
   * $getFieldIf a boolean-like field
   * (numeric or boolean).
   */
  def booleanLike(name: String): Option[Boolean] = get(name) match {
    case Some(v) => // accept BSONNull
      BSONBooleanLike.Handler.readTry(v).flatMap(_.toBoolean).toOption

    case _ =>
      None
  }

  /**
   * $getFieldIf a nested document.
   */
  def child(name: String): Option[BSONDocument] = get(name).collect {
    case doc: BSONDocument => doc
  }

  /**
   * $getFieldIf a list of nested documents.
   */
  def children(name: String): List[BSONDocument] =
    get(name).toList.flatMap {
      case arr: BSONArray => arr.values.collect {
        case doc: BSONDocument => doc
      }

      case _ =>
        List.empty[BSONDocument]

    }

  /**
   * $getFieldIf a double field.
   */
  def double(name: String): Option[Double] = getAsOpt[Double](name)

  /**
   * $getFieldIf a integer field.
   */
  def int(name: String): Option[Int] = getAsOpt[Int](name)

  /**
   * $getFieldIf a long field.
   */
  def long(name: String): Option[Long] = getAsOpt[Long](name)

  /**
   * $getFieldIf a string field.
   */
  def string(name: String): Option[String] = getAsOpt[String](name)

  /**
   * $getFieldIf a binary/uuid field.
   */
  def uuid(name: String): Option[UUID] = document.getAsOpt[UUID](name)

  /**
   * '''EXPERIMENTAL:''' Returns a strict representation
   * (with only the last value kept per each field name).
   *
   * {{{
   * reactivemongo.api.bson.BSONDocument(
   *   "foo" -> 1, "bar" -> 2, "foo" -> 3).asStrict
   * // { 'foo': 3, 'bar': 2 }
   * }}}
   */
  def asStrict: BSONDocument.Strict = {
    val ns = MSet.empty[String]
    val elms = self.elements.reverse.filter { ns add _.name }.reverse

    new BSONDocument with BSONStrictDocument {
      val elements = elms
      lazy val fields = BSONDocument.toMap(elms)
      val isEmpty = elms.isEmpty
      @inline def headOption = self.elements.headOption
    }
  }
}

private[bson] sealed trait BSONDocumentLowPriority { self: BSONDocument =>
  /**
   * Creates a new [[BSONDocument]] containing all the elements
   * of this one and the specified element producers.
   *
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * val doc = BSONDocument("foo" -> 1)
   *
   * doc ++ ("bar" -> "lorem") // { 'foo': 1, 'bar': 'lorem' }
   * }}}
   */
  def ++(producers: ElementProducer*): BSONDocument = new BSONDocument {
    val elements = (toLazy(self.elements) ++ toLazy(
      producers).flatMap(_.generate())).distinct

    lazy val fields = BSONDocument.toMap(elements)

    val isEmpty = self.elements.isEmpty && producers.isEmpty
    @inline def headOption = self.elements.headOption
  }
}

/**
 * '''EXPERIMENTAL:''' Strict documentation representation with at most
 * one value per field name (no duplicate).
 *
 * {{{
 * import reactivemongo.api.bson.BSONDocument
 *
 * def strict1 = // { 'foo': 1 }
 *   BSONDocument.strict("foo" -> 1, "foo" -> 2)
 *
 * def strict2 = BSONDocument("foo" -> 1, "foo" -> 2).asStrict
 *
 * assert(strict1 == strict2)
 * }}}
 */
sealed trait BSONStrictDocument
  extends BSONStrictDocumentLowPriority { self: BSONDocument =>

  /**
   * Concatenate the two documents, maintaining field unicity
   * by keeping only the last value per each name.
   *
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * BSONDocument("foo" -> 1, "bar" -> 2) ++ BSONDocument("foo" -> 4)
   * // { 'foo': 4, 'bar': 2 }
   * }}}
   */
  final override def ++(doc: BSONDocument): BSONDocument = new BSONDocument {
    lazy val fields: Map[String, BSONValue] = self.fields ++ doc.fields

    val elements = BSONDocument.dedupElements(
      toLazy(self.elements) ++ toLazy(doc.elements))

    @inline def headOption = self.headOption
    val isEmpty = self.isEmpty && doc.isEmpty
  }

  /**
   * Appends the given elements to the current document,
   * or update the value for an already known field to maintain unicity.
   *
   * {{{
   * reactivemongo.api.bson.BSONDocument(
   *   "foo" -> 1, "bar" -> 2) ++ ("foo" -> 4)
   * // { 'foo': 4, 'bar': 2 }
   * }}}
   */
  final override def ++(seq: BSONElement*): BSONDocument = new BSONDocument {
    val elements = BSONDocument.dedupElements(
      toLazy(self.elements) ++ toLazy(seq))

    lazy val fields = {
      val m = MMap.empty[String, BSONValue]

      m ++= self.fields

      seq.foreach {
        case BSONElement(name, value) => m.put(name, value)
      }

      m.toMap
    }

    @inline def headOption = self.headOption
    val isEmpty = self.isEmpty && seq.isEmpty
  }

  final override def --(keys: String*): BSONDocument.Strict =
    new BSONDocument with BSONStrictDocument {
      val fields = self.fields -- keys
      lazy val elements = self.elements.filterNot { e => keys.contains(e.name) }
      @inline def headOption = elements.headOption
      val isEmpty = fields.isEmpty
    }

  final override val asStrict: BSONDocument.Strict = this
}

private[bson] sealed trait BSONStrictDocumentLowPriority {
  self: BSONDocument.Strict =>

  final override def ++(producers: ElementProducer*): BSONDocument =
    new BSONDocument {
      val elements = BSONDocument.dedupProducers(
        toLazy(self.elements) ++ toLazy(producers).flatMap(_.generate()))

      lazy val fields = BSONDocument.toMap(elements)

      val isEmpty = self.elements.isEmpty && producers.isEmpty
      @inline def headOption = self.elements.headOption
    }
}

/**
 * [[BSONDocument]] factories & utilities.
 *
 * {{{
 * reactivemongo.api.bson.BSONDocument("foo" -> 1, "bar" -> "lorem")
 * }}}
 *
 * @define elementsFactoryDescr Creates a new [[BSONDocument]] containing the unique elements from the given collection (only one instance of a same element, same name & value, is kept).
 *
 * @define strictFactoryDescr Creates a new [[BSONDocument]] containing the elements deduplicated by name from the given collection (only the last is kept for a same name). Then append operations on such document will maintain element unicity by field name (see `BSONStrictDocument.++`).
 */
object BSONDocument {
  /**
   * Extracts the elements if `that`'s a [[BSONDocument]].
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONDocument, BSONValue }
   *
   * def names(v: BSONValue): Seq[String] = v match {
   *   case BSONDocument(elements) => elements.map(_.name)
   *   case _ => Seq.empty[String]
   * }
   * }}}
   */
  def unapply(that: Any): Option[Seq[BSONElement]] = that match {
    case doc: BSONDocument => Some(doc.elements)
    case _ => None
  }

  /**
   * $elementsFactoryDescr
   *
   * {{{
   * reactivemongo.api.bson.BSONDocument(
   *   "foo" -> 1, "bar" -> "lorem"
   * ) // => { 'foo': 1, 'bar': 'lorem' }
   * }}}
   */
  def apply(elms: ElementProducer*): BSONDocument = new BSONDocument {
    val elements = toLazy(elms).distinct.flatMap(_.generate())
    lazy val fields = BSONDocument.toMap(elms)
    val isEmpty = elms.isEmpty
    @inline def headOption = elements.headOption
  }

  /**
   * $elementsFactoryDescr
   *
   * {{{
   * import reactivemongo.api.bson._
   *
   * BSONDocument(Seq(
   *   "foo" -> BSONInteger(1), "bar" -> BSONString("lorem")))
   * // { 'foo': 1, 'bar': 'lorem' }
   * }}}
   */
  def apply(elms: Iterable[(String, BSONValue)]): BSONDocument =
    new BSONDocument {
      val pairs = toLazy(elms).distinct
      val elements = pairs.map {
        case (k, v) => BSONElement(k, v)
      }

      lazy val fields = {
        val m = MMap.empty[String, BSONValue]

        pairs.foreach {
          case (k, v) => m.put(k, v)
        }

        m.toMap
      }

      val isEmpty = elms.isEmpty

      @inline def headOption = elements.headOption
    }

  /**
   * '''EXPERIMENTAL:''' $strictFactoryDescr
   *
   * {{{
   * reactivemongo.api.bson.BSONDocument("foo" -> 1, "bar" -> 2, "foo" -> 3)
   * // => { "foo": 1, "bar": 2 } : No "foo": 3
   * }}}
   */
  def strict(elms: ElementProducer*): BSONDocument.Strict =
    new BSONDocument with BSONStrictDocument {
      val elements = dedupProducers(toLazy(elms))
      lazy val fields = BSONDocument.toMap(elements)
      val isEmpty = elms.isEmpty
      @inline def headOption = elements.headOption
    }

  /**
   * '''EXPERIMENTAL:''' $strictFactoryDescr
   *
   * {{{
   * import reactivemongo.api.bson._
   *
   * BSONDocument.strict(Seq(
   *   "foo" -> BSONInteger(1), "foo" -> BSONString("lorem")))
   * // { 'foo': 1 }
   * }}}
   */
  def strict(elms: Iterable[(String, BSONValue)]): BSONDocument.Strict =
    new BSONDocument with BSONStrictDocument {
      val pairs = {
        val ns = MSet.empty[String]

        toLazy(elms).reverse.filter { ns add _._1 }.reverse
      }

      val elements = pairs.map {
        case (k, v) => BSONElement(k, v)
      }

      lazy val fields = {
        val m = MMap.empty[String, BSONValue]

        pairs.foreach {
          case (k, v) => m.put(k, v)
        }

        m.toMap
      }

      val isEmpty = elms.isEmpty

      @inline def headOption = elements.headOption
    }

  /**
   * Returns a String representing the given [[BSONDocument]].
   *
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * def printDoc(doc: BSONDocument): String = BSONDocument.pretty(doc)
   * }}}
   *
   * @see [[BSONValue$.pretty]]
   */
  def pretty(doc: BSONDocument): String = BSONIterator.pretty(doc.elements)

  /** '''EXPERIMENTAL''' */
  type Strict = BSONDocument with BSONStrictDocument

  /** Internal (optimized/eager) factory. */
  private[bson] def apply(
    elms: Seq[BSONElement],
    fs: Map[String, BSONValue]): BSONDocument = new BSONDocument {
    val elements = elms
    lazy val fields = fs
    val isEmpty = elms.isEmpty
    @inline def headOption = elements.headOption
  }

  /** Internal (optimized/eager) strict factory. */
  private[bson] def strict(
    elms: Seq[BSONElement],
    fs: Map[String, BSONValue]): BSONDocument.Strict =
    new BSONDocument with BSONStrictDocument {
      val elements = elms
      lazy val fields = fs
      val isEmpty = elms.isEmpty
      @inline def headOption = elements.headOption
    }

  /** An empty BSONDocument. */
  val empty: BSONDocument = new BSONDocument {
    val fields = HashMap.empty[String, BSONValue]
    val elements = Seq.empty[BSONElement]
    val isEmpty = true
    override val size = 0
    val headOption = Option.empty[BSONElement]
  }

  /**
   * '''EXPERIMENTAL:''' Returns a [[BSONDocument]] builder.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONDocument, ElementProducer }
   *
   * val builder = BSONDocument.newBuilder
   *
   * builder += ("foo" -> 1)
   * builder ++= Seq("bar" -> "lorem", "ipsum" -> 3.45D)
   * builder ++= Some[ElementProducer]("dolor" -> 6L)
   *
   * // builder.result() == {'foo':1, 'bar':'lorem', 'ipsum':3.45, 'dolor':6}
   * }}}
   */
  def newBuilder: MBuilder[ElementProducer, BSONDocument] = new DocumentBuilder

  // ---

  private[bson] def toMap(seq: Iterable[ElementProducer]): Map[String, BSONValue] = {
    val m = MMap.empty[String, BSONValue]

    seq.foreach {
      case BSONElement(k, v) => m.put(k, v)
      case _: ElementProducer.Empty.type => ()

      case p => p.generate().foreach {
        case BSONElement(k, v) => m.put(k, v)
      }
    }

    m.toMap
  }

  private[bson] def dedupElements(in: Seq[BSONElement]): IndexedSeq[BSONElement] = {
    var elms = scala.collection.mutable.IndexedSeq.empty[BSONElement]
    val indexes = MMap.empty[String, Int]

    var i = -1
    in.foreach { elm =>
      i += 1

      val idx = indexes.getOrElseUpdate(elm.name, i)

      if (idx == i) { // new field name
        elms = elms.padTo(idx + 1, elm)
      } else { // update field
        elms.update(idx, elm)
      }
    }

    elms.toIndexedSeq
  }

  private[bson] def dedupProducers(in: LinearSeq[ElementProducer]): LinearSeq[BSONElement] = {
    val ns = MSet.empty[String]

    in.flatMap(_.generate()).reverse.filter { ns add _.name }.reverse
  }
}

/**
 * BSON element, typically a pair of name and [[BSONValue]].
 *
 * {{{
 * import reactivemongo.api.bson.{ BSONElement, BSONString }
 *
 * BSONElement("name", BSONString("value"))
 * }}}
 */
sealed abstract class BSONElement extends ElementProducer {
  /** Element (field) name */
  def name: String

  /** Element (BSON) value */
  def value: BSONValue

  final def generate() = Some(this)

  override final lazy val hashCode: Int = {
    import scala.util.hashing.MurmurHash3

    val nh = MurmurHash3.mix(MurmurHash3.productSeed, name.##)

    MurmurHash3.mixLast(nh, value.hashCode)
  }

  override final def equals(that: Any): Boolean = that match {
    case other: BSONElement =>
      (name == other.name && value == other.value)

    case _ => false
  }

  override final def toString = s"BSONElement($name -> $value)"
}

/** [[BSONElement]] factories and utilities. */
object BSONElement extends BSONElementLowPriority {
  /**
   * Extracts the name and [[BSONValue]] if `that`'s a [[BSONElement]].
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONDocument, BSONElement }
   *
   * def foo(doc: BSONDocument): Unit = doc.elements.foreach {
   *   case BSONElement(name, bson) => println(s"- " + name + " = " + bson)
   * }
   * }}}
   */
  def unapply(that: Any): Option[(String, BSONValue)] = that match {
    case elmt: BSONElement => Some(elmt.name -> elmt.value)
    case _ => None
  }

  /**
   * Create a new [[BSONElement]].
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONElement, BSONLong }
   *
   * BSONElement("name", BSONLong(2L))
   * }}}
   */
  def apply(name: String, value: BSONValue): BSONElement =
    new DefaultElement(name, value)

  /** Returns an empty [[ElementProducer]] */
  def apply: (String, None.type) => ElementProducer =
    { (_, _) => ElementProducer.Empty }

  private final class DefaultElement(
    val name: String,
    val value: BSONValue) extends BSONElement
}

private[bson] sealed trait BSONElementLowPriority { _: BSONElement.type =>
  /**
   * Returns a [[ElementProducer]] for the given name and value.
   *
   * {{{
   * import reactivemongo.api.bson.{ BSONElement, ElementProducer }
   *
   * val e1: ElementProducer = BSONElement("name1", 1) // BSONInteger
   * val e2 = BSONElement("name2", "foo") // BSONString
   * }}}
   */
  def apply[T](name: String, value: T)(implicit ev: T => Producer[BSONValue]): ElementProducer = {
    val produced = ev(value).generate()

    produced.headOption match {
      case Some(v) if produced.tail.isEmpty =>
        BSONElement(name, v)

      case Some(_) =>
        BSONDocument(produced.map { name -> _ })

      case _ =>
        ElementProducer.Empty
    }
  }

  // Conversions
  /**
   * {{{
   * import reactivemongo.api.bson.{ BSONElement, BSONInteger }
   *
   * val e: BSONElement = "foo" -> BSONInteger(1)
   * // tuple as BSONElement("foo", BSONInteger(1))
   * }}}
   */
  implicit def bsonTuple2BSONElement(pair: (String, BSONValue)): BSONElement =
    BSONElement(pair._1, pair._2)

}

/** See [[BSONDocument]] */
sealed trait ElementProducer extends Producer[BSONElement]

object ElementProducer extends ElementProducerLowPriority {
  /**
   * An empty instance for the [[ElementProducer]] kind.
   * Can be used as `id` with the element [[Composition]] to form
   * an additive monoid.
   */
  private[bson] object Empty extends ElementProducer {
    private val underlying = Seq.empty[BSONElement]
    def generate() = underlying
  }

  private[bson] def apply(elements: Iterable[BSONElement]): ElementProducer =
    new ElementProducer {
      def generate() = elements

      override def equals(that: Any): Boolean = that match {
        case other: ElementProducer => other.generate() == elements
        case _ => false
      }

      override def hashCode: Int = elements.hashCode

      override def toString = s"ElementProducer(${elements.mkString(", ")})"
    }

  /**
   * A composition operation for [[ElementProducer]],
   * so that it forms an additive monoid with the `Empty` instance as `id`.
   */
  object Composition
    extends ((ElementProducer, ElementProducer) => ElementProducer) {

    def apply(x: ElementProducer, y: ElementProducer): ElementProducer =
      (x, y) match {
        case (Empty, Empty) => Empty
        case (Empty, _) => y
        case (_, Empty) => x

        case (a: BSONDocument, b: BSONDocument) => a ++ b

        case (doc: BSONDocument, e: BSONElement) =>
          new BSONDocument {
            val fields = doc.fields.updated(e.name, e.value)
            val elements = (toLazy(doc.elements) :+ e).distinct
            val isEmpty = false // at least `e`
            @inline def headOption = doc.headOption
          }

        case (e: BSONElement, doc: BSONDocument) =>
          new BSONDocument {
            lazy val fields = {
              val m = MMap.empty[String, BSONValue]

              (e +: doc.elements).foreach { el =>
                m.put(el.name, el.value)
              }

              m.toMap
            }

            val elements = (e +: doc.elements).distinct
            val isEmpty = false // at least `e`
            @inline def headOption = Some(e)
          }

        case _ => BSONDocument(x, y)
      }
  }

  // Conversions

  /**
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * BSONDocument(
   *   "foo" -> None // tuple as empty ElementProducer (no field)
   * )
   * }}}
   */
  implicit val noneValue2ElementProducer: ((String, None.type)) => ElementProducer = _ => ElementProducer.Empty

  /**
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * BSONDocument(
   *   "foo" -> Some(1), // tuple as BSONElement("foo", BSONInteger(1))
   *   "bar" -> Option.empty[Int] // tuple as empty ElementProducer (no field)
   * )
   * }}}
   */
  implicit def nameOptionValue2ElementProducer[T](element: (String, Option[T]))(implicit writer: BSONWriter[T]): ElementProducer = (for {
    v <- element._2
    b <- writer.writeOpt(v)
  } yield BSONElement(element._1, b)).getOrElse(ElementProducer.Empty)
}

private[bson] sealed trait ElementProducerLowPriority {
  _: ElementProducer.type =>

  /**
   * {{{
   * import reactivemongo.api.bson.BSONDocument
   *
   * BSONDocument(
   *   "foo" -> 1 // tuple as BSONElement("foo", BSONInteger(1))
   * )
   * }}}
   */
  implicit def tuple2ElementProducer[T](pair: (String, T))(implicit writer: BSONWriter[T]): ElementProducer = writer.writeOpt(pair._2) match {
    case Some(v) => BSONElement(pair._1, v)
    case _ => ElementProducer.Empty
  }
}

/**
 * A BSON value that can be seen as a number.
 *
 * Conversions:
 *   - numeric BSON types ([[BSONDecimal]], [[BSONDouble]], [[BSONInteger]], [[BSONLong]])
 *   - BSON date/time ~> long ([[BSONDateTime]]`.value`)
 *   - BSON timestamp ~> long ([[BSONTimestamp.value]])
 *
 * {{{
 * import scala.util.Success
 * import reactivemongo.api.bson.{ BSONNumberLike, BSONDocument, BSONInteger }
 *
 * val bi = BSONInteger(1)
 * assert(bi.asTry[BSONNumberLike].flatMap(_.toLong) == Success(1L))
 *
 * val doc = BSONDocument("field" -> bi)
 * assert(doc.getAsTry[BSONNumberLike]("field").
 *   flatMap(_.toDouble) == Success(1D))
 * }}}
 */
sealed trait BSONNumberLike {
  /** Converts this number into an `Int`. */
  def toInt: Try[Int]

  /** Converts this number into a `Long`. */
  def toLong: Try[Long]

  /** Converts this number into a `Float`. */
  def toFloat: Try[Float]

  /** Converts this number into a `Double`. */
  def toDouble: Try[Double]

  private[bson] def numberValue: BSONValue
}

/** [[BSONNumberLike]] utilities */
object BSONNumberLike {
  implicit object Handler extends BSONReader[BSONNumberLike]
    with BSONWriter[BSONNumberLike] with SafeBSONWriter[BSONNumberLike] {

    def readTry(bson: BSONValue): Try[BSONNumberLike] = bson match {
      case n: BSONNumberLike => Success(n)

      case _ => Failure(
        TypeDoesNotMatchException(
          "<number>", bson.getClass.getSimpleName))
    }

    override def readOpt(bson: BSONValue): Option[BSONNumberLike] = bson match {
      case n: BSONNumberLike => Some(n)
      case _ => None
    }

    def safeWrite(n: BSONNumberLike) = n.numberValue
  }

  // ---

  private[bson] sealed trait Value extends BSONNumberLike { self: BSONValue =>
    @inline private[bson] def numberValue = self
  }
}

/**
 * A BSON value that can be seen as a boolean.
 *
 * Conversions:
 *   - `number = 0 ~> false`
 *   - `number != 0 ~> true`
 *   - `boolean`
 *   - `undefined ~> false`
 *   - `null ~> false`
 *
 * {{{
 * import scala.util.Success
 * import reactivemongo.api.bson.{ BSONBooleanLike, BSONDocument, BSONInteger }
 *
 * val bi = BSONInteger(1)
 * assert(bi.asTry[BSONBooleanLike].flatMap(_.toBoolean) == Success(true))
 *
 * val doc = BSONDocument("field" -> bi)
 * assert(doc.getAsTry[BSONBooleanLike]("field").
 *   flatMap(_.toBoolean) == Success(true))
 * }}}
 */
sealed trait BSONBooleanLike {
  private[bson] def boolValue: BSONValue

  /** Returns the boolean equivalent value */
  def toBoolean: Try[Boolean]
}

/** [[BSONBooleanLike]] utilities */
object BSONBooleanLike {
  implicit object Handler extends BSONReader[BSONBooleanLike]
    with BSONWriter[BSONBooleanLike] with SafeBSONWriter[BSONBooleanLike] {

    def readTry(bson: BSONValue): Try[BSONBooleanLike] = bson match {
      case b: BSONBooleanLike => Success(b)

      case _ => Failure(TypeDoesNotMatchException(
        "<boolean>", bson.getClass.getSimpleName))
    }

    override def readOpt(bson: BSONValue): Option[BSONBooleanLike] =
      bson match {
        case b: BSONBooleanLike => Some(b)
        case _ => None
      }

    def safeWrite(b: BSONBooleanLike) = b.boolValue
  }

  // ---

  private[bson] sealed trait Value extends BSONBooleanLike { self: BSONValue =>
    @inline private[bson] def boolValue = self
  }
}
