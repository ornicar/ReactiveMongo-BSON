import scala.util.{ Failure, Success, Try }

import com.github.ghik.silencer.silent

import reactivemongo.api.bson.{
  BSON,
  BSONDecimal,
  BSONDocument,
  BSONDocumentHandler,
  BSONDocumentReader,
  BSONDocumentWriter,
  BSONHandler,
  BSONNull,
  BSONReader,
  BSONValue,
  BSONWriter,
  FieldNaming,
  Macros,
  MacroConfiguration,
  MacroOptions,
  TypeNaming
}
import reactivemongo.api.bson.exceptions.TypeDoesNotMatchException

import org.specs2.matcher.MatchResult

final class MacroSpec extends org.specs2.mutable.Specification {
  "Macros" title

  import MacroTest._

  "Formatter" should {
    "handle primitives" in {
      roundtrip(
        Primitives(1.2, "hai", true, 42, Long.MaxValue),
        Macros.handler[Primitives])
    }

    "support nesting" in {
      implicit val personFormat = Macros.handler[Person]
      val doc = Pet("woof", Person("john", "doe"))

      roundtrip(doc, Macros.handler[Pet])
    }

    "support option" in {
      val format = Macros.handler[Optional]
      val some = Optional("some", Some("value"))
      val none = Optional("none", None)

      roundtrip(some, format) and roundtrip(none, format)
    }

    "not support type mismatch for optional value" in {
      Macros.reader[Optional].readTry(
        BSONDocument(
          "name" -> "invalidValueType",
          "value" -> 4)) must beFailedTry[Optional] //("BSONInteger")
    }

    "support null for optional value" in {
      Macros.reader[Optional].readTry(
        BSONDocument(
          "name" -> "name",
          "value" -> BSONNull)).
        map(_.value) must beSuccessfulTry(Option.empty[String])
    }

    "write empty option as null" in {
      Macros.writer[OptionalAsNull].writeTry(
        OptionalAsNull("asNull", None)) must beSuccessfulTry(BSONDocument(
          "name" -> "asNull",
          "value" -> BSONNull))
    }

    "support seq" in {
      roundtrip(
        WordLover("john", Seq("hello", "world")),
        Macros.handler[WordLover])
    }

    "support single member case classes" in {
      roundtrip(
        Single(BigDecimal("12.345")),
        Macros.handler[Single])
    }

    "support single member options" in {
      val f = Macros.handler[OptionalSingle]

      roundtrip(OptionalSingle(Some("foo")), f) and {
        roundtrip(OptionalSingle(None), f)
      }
    }

    "support generic optional value" >> {
      val doc1 = BSONDocument("v" -> 1)
      val none = OptionalGeneric[String](1, None)

      val doc2 = BSONDocument("v" -> 2, "opt" -> "foo")
      val some = OptionalGeneric(2, Some("foo"))

      "for reader" in {
        val reader = Macros.reader[OptionalGeneric[String]]

        reader.readTry(doc1) must beSuccessfulTry(none) and {
          reader.readTry(doc2).
            aka("some valid") must beSuccessfulTry(some)
        } and {
          reader.readTry(BSONDocument("v" -> 3, "opt" -> 4.5D)).
            aka("some invalid") must beFailedTry[OptionalGeneric[String]]
        }
      }

      "for writer" in {
        val writer = Macros.writer[OptionalGeneric[String]]

        writer.writeTry(none) must beSuccessfulTry(doc1) and {
          writer.writeTry(some) must beSuccessfulTry(doc2)
        }
      }

      "for handler" in {
        val h = Macros.handler[OptionalGeneric[String]]

        roundtrip(none, h) and roundtrip(some, h)
      }
    }

    "support generic case class Foo" >> {
      implicit def singleHandler = Macros.handler[Single]

      "directly" in {
        roundtrip(
          Foo(Single(BigDecimal(123L)), "ipsum"),
          Macros.handler[Foo[Single]])
      }

      "from generic function" in {
        def handler[T](implicit w: BSONDocumentWriter[T], r: BSONDocumentReader[T]) = Macros.handler[Foo[T]]

        roundtrip(Foo(Single(BigDecimal(1.23D)), "ipsum"), handler[Single])
      }
    }

    "support generic case class GenSeq" in {
      implicit def singleHandler = new BSONWriter[Single] with BSONReader[Single] with BSONHandler[Single] {
        def writeTry(single: Single) = BSONDecimal.fromBigDecimal(single.value)

        def readTry(bson: BSONValue): Try[Single] = bson match {
          case dec: BSONDecimal =>
            BSONDecimal.toBigDecimal(dec).map(Single(_))

          case _ =>
            Failure(TypeDoesNotMatchException(
              "BSONDecimal", bson.getClass.getSimpleName))
        }
      }

      implicit def optionHandler[T](implicit h: BSONHandler[T]): BSONDocumentHandler[Option[T]] = new BSONDocumentReader[Option[T]] with BSONDocumentWriter[Option[T]] with BSONHandler[Option[T]] {

        def readDocument(doc: BSONDocument): Try[Option[T]] =
          doc.getAsUnflattenedTry[T](f"$$some")

        def writeTry(single: Option[T]): Try[BSONDocument] = single match {
          case Some(v) => h.writeTry(v).map { x =>
            BSONDocument(f"$$some" -> x)
          }

          case _ => Success(BSONDocument.empty)
        }
      }

      def genSeqHandler[T: BSONDocumentHandler]: BSONDocumentHandler[GenSeq[T]] = Macros.handler[GenSeq[T]]

      val seq = GenSeq(
        items = Seq(Option.empty[Single], Option(Single(BigDecimal(1)))),
        count = 1)

      roundtrip(seq, genSeqHandler[Option[Single]])
    }

    "handle overloaded apply correctly" in {
      val doc1 = OverloadedApply("hello")
      val doc2 = OverloadedApply(List("hello", "world"))

      val f = Macros.handlerOpts[OverloadedApply, MacroOptions.DisableWarnings]

      roundtrip(doc1, f)
      roundtrip(doc2, f)
    }

    "handle overloaded apply with different number of arguments correctly" in {
      val doc1 = OverloadedApply2("hello", 5)
      val doc2 = OverloadedApply2("hello")
      val f = Macros.handler[OverloadedApply2]

      roundtrip(doc1, f)
      roundtrip(doc2, f)
    }

    "handle overloaded apply with 0 number of arguments correctly" in {
      val doc1 = OverloadedApply3("hello", 5)
      val doc2 = OverloadedApply3()
      val f = Macros.handler[OverloadedApply3]

      roundtrip(doc1, f)
      roundtrip(doc2, f)
    }

    "case class and handler inside trait" in {
      val t = new NestModule {}
      roundtrip(t.Nested("it works"), t.format)
    }

    "case class inside trait with handler outside" in {
      val t = new NestModule {}
      import t._ //you need Nested in scope because t.Nested won't work
      val format = Macros.handler[Nested]
      roundtrip(Nested("it works"), format)
    }

    "respect compilation options" in {
      val format = Macros.handlerOpts[Person, MacroOptions.Verbose] //more stuff in compiler log

      roundtrip(Person("john", "doe"), format)
    }

    "not persist class name for case class" in {
      val person = Person("john", "doe")
      val format = Macros.handler[Person]

      val doc = format writeTry person

      doc.flatMap(_.getAsUnflattenedTry[String]("className")).
        aka("class") must beSuccessfulTry(Option.empty[String]) and {
          roundtrip(person, format)
        }
    }

    "respect field naming" >> {
      val person = Person("Jane", "doe")

      def spec(
        handler: BSONDocumentHandler[Person],
        expectedBson: BSONDocument) = {
        handler.writeTry(person) must beSuccessfulTry(expectedBson) and {
          handler.readTry(expectedBson) must beSuccessfulTry(person)
        }
      }

      "with default configuration" in {
        spec(
          handler = Macros.handler[Person],
          expectedBson = BSONDocument(
            "firstName" -> "Jane",
            "lastName" -> "doe"))

      }

      "with implicit configuration (PascalCase)" in {
        implicit def cfg: MacroConfiguration = MacroConfiguration(
          fieldNaming = FieldNaming.PascalCase)

        spec(
          handler = Macros.handler[Person],
          expectedBson = BSONDocument(
            "FirstName" -> "Jane",
            "LastName" -> "doe"))

      }

      "with macro-configured handler (SnakeCase)" in {
        spec(
          handler = Macros.configured(MacroConfiguration(
            fieldNaming = FieldNaming.SnakeCase)).handler[Person],
          expectedBson = BSONDocument(
            "first_name" -> "Jane",
            "last_name" -> "doe"))
      }
    }

    "handle union types (ADT)" >> {
      import Union._
      import MacroOptions._

      "with custom discriminator" in {
        val a = UA(1)
        val b = UB("hai")

        implicit val cfg = MacroConfiguration(discriminator = "_type")

        val format = Macros.handlerOpts[UT, UnionType[UA \/ UB \/ UC \/ UD \/ UF.type] with AutomaticMaterialization]

        format.writeTry(a).map(_.getAsOpt[String]("_type")).
          aka("class #1") must beSuccessfulTry(Some("MacroTest.Union.UA")) and {
            format.writeTry(b).map(_.getAsOpt[String]("_type")).
              aka("class #2") must beSuccessfulTry(Some("MacroTest.Union.UB"))
          } and roundtrip(a, format) and roundtrip(b, format)
      }

      "with simple type names" in {
        import Union._
        import MacroOptions._
        val a = UA(1)
        val b = UB("hai")

        implicit def config = MacroConfiguration.simpleTypeName

        val format = Macros.handlerOpts[UT, UnionType[UA \/ UB \/ UC \/ UD] with AutomaticMaterialization]

        format.writeTry(a).flatMap(_.getAsTry[String]("className")).
          aka("discriminator UA") must beSuccessfulTry("UA") and {
            format.writeTry(b).flatMap(_.getAsTry[String]("className")).
              aka("discriminator UB") must beSuccessfulTry("UB")
          } and roundtrip(a, format) and roundtrip(b, format)
      }

      "without sealed trait" in {
        import Union._
        import MacroOptions._

        val a = UA2(1)
        val b = UB2("hai")

        val format = Macros.handlerOpts[UT2, UnionType[UA2 \/ UB2] with AutomaticMaterialization]

        format.writeTry(a).map(_.getAsOpt[String]("className")).
          aka("class #1") must beSuccessfulTry(
            Some("MacroTest.Union.UA2")) and {
              format.writeTry(b).map(_.getAsOpt[String]("className")).
                aka("class #2") must beSuccessfulTry(Some("MacroTest.Union.UB2"))
            } and roundtrip(a, format) and roundtrip(b, format)
      }
    }

    "handle recursive structure" in {
      import TreeModule._
      //handlers defined at tree module
      val tree: Tree = Node(Leaf("hi"), Node(Leaf("hello"), Leaf("world")))
      roundtrip(tree, Tree.bson)
    }

    "grab an implicit handler for type used in union" in {
      import TreeCustom._
      val tree: Tree = Node(Leaf("hi"), Node(Leaf("hello"), Leaf("world")))
      val serialized = BSON writeDocument tree
      val deserialized = serialized.flatMap(BSON.readDocument[Tree](_))

      deserialized must beSuccessfulTry(
        Node(Leaf("hai"), Node(Leaf("hai"), Leaf("hai"))))
    }

    "handle empty case classes" in {
      val empty = Empty()
      val format = Macros.handler[Empty]

      roundtrip(empty, format)
    }

    "do nothing with objects" in {
      val format = Macros.handler[EmptyObject.type]
      roundtrip(EmptyObject, format)
    }

    "handle ADTs with objects" in {
      import IntListModule._

      roundtripImp[IntList](Tail) and {
        roundtripImp[IntList](Cons(1, Cons(2, Cons(3, Tail))))
      }
    }

    "automate Union on sealed traits" >> {
      import MacroOptions._
      import Union._

      // when no implicit for sub-type (w/o auto-materialization)
      shapeless.test.illTyped("Macros.handler[UT]")

      "with auto-materialization & default configuration" in {
        implicit val format = Macros.handlerOpts[UT, AutomaticMaterialization with DisableWarnings /* disabled: Cannot handle object MacroTest\\.Union\\.UE */ ]

        format.writeTry(UA(1)).flatMap(_.getAsTry[String]("className")).
          aka("class #1") must beSuccessfulTry("MacroTest.Union.UA") and {
            format.writeTry(UB("buzz")).flatMap(
              _.getAsUnflattenedTry[String]("className")).
              aka("class #2") must beSuccessfulTry(Some("MacroTest.Union.UB"))

          } and roundtripImp[UT](UA(17)) and roundtripImp[UT](UB("foo")) and {
            roundtripImp[UT](UC("bar")) and roundtripImp[UT](UD("baz"))
          } and roundtripImp[UT](UF)
      }

      "with custom configuration" in {
        // For sub-type UA
        implicit def aReader = Macros.reader[UA]
        implicit def aWriter = Macros.writer[UA]

        // Sub-type UC, UD, UF
        implicit def cHandler = Macros.handler[UC]
        implicit def dHandler = Macros.handler[UD]
        implicit def fHandler = Macros.handler[UF.type]

        @silent("Cannot handle object MacroTest\\.Union\\.UE" /*expected*/ )
        implicit val format: BSONDocumentHandler[UT] = {
          implicit val cfg: MacroConfiguration = MacroConfiguration(
            discriminator = "_type",
            typeNaming = TypeNaming.SimpleName.andThen(_.toLowerCase))

          Macros.handler[UT]
        }

        format.writeTry(UA(1)).flatMap(_.getAsTry[String]("_type")).
          aka("custom discriminator") must beSuccessfulTry("ua") and {
            format.writeTry(UB("fuzz")).flatMap(
              _.getAsUnflattenedTry[String]("_type")).
              aka("class #2") must beSuccessfulTry(Some("ub"))

          } and roundtripImp[UT](UA(17)) and roundtripImp[UT](UB("foo")) and {
            roundtripImp[UT](UC("bar")) and roundtripImp[UT](UD("baz"))
          } and roundtripImp[UT](UF)

      }
    }

    "support automatic implementations search with nested traits" in {
      import MacroOptions._
      import InheritanceModule._
      implicit val format = Macros.handlerOpts[T, AutomaticMaterialization]

      format.writeTry(A()).flatMap(_.getAsTry[String]("className")).
        aka("class #1") must beSuccessfulTry(
          "MacroTest.InheritanceModule.A") and {
            format.writeOpt(B).flatMap(_.getAsOpt[String]("className")).
              aka("class #2") must beSome("MacroTest.InheritanceModule.B")

          } and {
            roundtripImp[T](A()) and roundtripImp[T](B) and roundtripImp[T](C())
          }
    }

    "automate Union on sealed traits with simple name" in {
      import MacroOptions._
      import Union._

      val configuredMacros = Macros.configured(
        MacroConfiguration[AutomaticMaterialization](
          typeNaming = TypeNaming.SimpleName))

      @silent("Cannot handle object MacroTest\\.Union\\.UE" /*expected*/ )
      implicit val format = configuredMacros.handler[UT]

      format.writeTry(UA(1)).flatMap(
        _.getAsTry[String]("className")) must beSuccessfulTry("UA") and {
          format.writeOpt(UB("buzz")).flatMap(
            _.getAsOpt[String]("className")) must beSome("UB")
        } and {
          roundtripImp[UT](UA(17)) and roundtripImp[UT](UB("foo")) and {
            roundtripImp[UT](UC("bar")) and roundtripImp[UT](UD("baz"))
          }
        }
    }

    "support automatic implementations search with nested traits with simple name" in {
      import InheritanceModule._

      implicit val format = Macros.configured(
        MacroConfiguration.simpleTypeName).handler[T]

      format.writeTry(A()).flatMap(
        _.getAsTry[String]("className")) must beSuccessfulTry("A") and {
          format.writeTry(B).flatMap(
            _.getAsTry[String]("className")) must beSuccessfulTry("B")

        } and {
          roundtripImp[T](A()) and roundtripImp[T](B) and roundtripImp[T](C())
        }
    }

    "support overriding keys with annotations" in {
      implicit val format = Macros.handler[RenamedId]
      val doc = RenamedId(value = "some value")
      val serialized = BSONDocument("_id" -> doc.myID, "value" -> doc.value)

      format.writeTry(doc) must beSuccessfulTry(serialized) and {
        format.readTry(serialized) must beSuccessfulTry(doc)
      }
    }

    "skip ignored fields" >> {
      "with Pair type" in {
        val pairHandler = Macros.handler[Pair]
        val doc = pairHandler.writeTry(Pair(left = "left", right = "right"))

        doc must beSuccessfulTry(BSONDocument("right" -> "right"))
      }

      "along with Key annotation" in {
        // TODO: Macros reader (manage Ignore with reader?)

        implicit val handler: BSONDocumentWriter[IgnoredAndKey] =
          Macros.writer[IgnoredAndKey]
        //TODO:Macros.handler[IgnoredAndKey]

        val expected = BSONDocument("second" -> "foo")
        val v = IgnoredAndKey(Person("john", "doe"), "foo")

        handler.writeTry(v) must beSuccessfulTry(expected) and {
          todo //TODO: handler.readTry(expected) must beSuccessfulTry(v)
        }
      }
    }

    "be generated for class class with self reference" in {
      val h = Macros.handler[Bar]
      val bar1 = Bar("bar1", None)
      val doc1 = BSONDocument("name" -> "bar1")

      h.readTry(doc1) must beSuccessfulTry(bar1) and {
        h.readTry(BSONDocument("name" -> "bar2", "next" -> doc1)).
          aka("bar2") must beSuccessfulTry(Bar("bar2", Some(bar1)))

      } and (h.writeTry(bar1) must beSuccessfulTry(doc1)) and {
        h.writeTry(Bar("bar2", Some(bar1))) must beSuccessfulTry(BSONDocument(
          "name" -> "bar2", "next" -> doc1))
      }
    }

    "support @Flatten annotation" in {
      shapeless.test.illTyped("Macros.handler[InvalidRecursive]")
      shapeless.test.illTyped("Macros.handler[InvalidNonDoc]")

      roundtrip(
        LabelledRange("range1", Range(start = 2, end = 5)),
        Macros.handler[LabelledRange])
    }

    "handle case class with implicits" >> {
      val doc1 = BSONDocument("pos" -> 2, "text" -> "str")
      val doc2 = BSONDocument("ident" -> "id", "value" -> 23.456D)
      val fixture1 = WithImplicit1(2, "str")
      val fixture2 = WithImplicit2("id", 23.456D)

      def readSpec1(r: BSONDocumentReader[WithImplicit1]) =
        r.readTry(doc1) must beSuccessfulTry(fixture1)

      def writeSpec2(w: BSONDocumentWriter[WithImplicit2[Double]]) =
        w.writeTry(fixture2) must beSuccessfulTry(doc2)

      "to generate reader" in readSpec1(Macros.reader[WithImplicit1])

      "to generate writer with type parameters" in writeSpec2(
        Macros.writer[WithImplicit2[Double]])

      "to generate handler" in {
        val f1 = Macros.handler[WithImplicit1]
        val f2 = Macros.handler[WithImplicit2[Double]]

        readSpec1(f1) and {
          f1.writeTry(fixture1) must beSuccessfulTry(doc1)
        } and {
          writeSpec2(f2)
        } and {
          f2.readTry(doc2) must beSuccessfulTry(fixture2)
        }
      }
    }
  }

  "Reader" should {
    "throw meaningful exception if required field is missing" in {
      val personDoc = BSONDocument("firstName" -> "joe")

      Macros.reader[Person].readTry(personDoc) must beFailedTry[Person].
        like { case e => e.getMessage must contain("lastName") }
    }

    "be generated to supported similar numeric BSON types" in {
      val primitivesDoc = BSONDocument(
        "dbl" -> 2D, "str" -> "str", "bl" -> true, "int" -> 2D, "long" -> 2L)

      Macros.reader[Primitives].readTry(primitivesDoc).
        aka("read") must beSuccessfulTry(Primitives(
          dbl = 2D, str = "str", bl = true, int = 2, long = 2L))
    }

    "be generated for a generic case class" in {
      implicit def singleReader = Macros.reader[Single]
      val r = Macros.reader[Foo[Single]]
      val big = BigDecimal(1.23D)

      r.readTry(BSONDocument(
        "bar" -> BSONDocument("value" -> big),
        "lorem" -> "ipsum")) must beSuccessfulTry(Foo(Single(big), "ipsum"))
    }

    "be generated for class class with self reference" in {
      val r = Macros.reader[Bar]
      val bar1 = Bar("bar1", None)
      val doc1 = BSONDocument("name" -> "bar1")

      r.readTry(doc1) must beSuccessfulTry(bar1) and {
        r.readTry(BSONDocument("name" -> "bar2", "next" -> doc1)).
          aka("bar2") must beSuccessfulTry(Bar("bar2", Some(bar1)))
      }
    }

    "be generated with @Flatten annotation" in {
      shapeless.test.illTyped("Macros.reader[InvalidRecursive]")
      shapeless.test.illTyped("Macros.reader[InvalidNonDoc]")

      val r = Macros.reader[LabelledRange]
      val doc = BSONDocument("name" -> "range1", "start" -> 2, "end" -> 5)
      val lr = LabelledRange("range1", Range(start = 2, end = 5))

      r.readTry(doc) must beSuccessfulTry(lr)
    }
  }

  "Writer" should {
    "be generated for a generic case class" in {
      implicit def singleWriter = Macros.writer[Single]
      val w = Macros.writer[Foo[Single]]

      w.writeTry(Foo(Single(BigDecimal(1)), "ipsum")) must beSuccessfulTry(
        BSONDocument(
          "bar" -> BSONDocument("value" -> BigDecimal(1)),
          "lorem" -> "ipsum"))
    }

    "be generated for class class with self reference" in {
      val w = Macros.writer[Bar]
      val bar1 = Bar("bar1", None)
      val doc1 = BSONDocument("name" -> "bar1")

      w.writeTry(bar1) must beSuccessfulTry(doc1) and {
        w.writeTry(Bar("bar2", Some(bar1))) must beSuccessfulTry(BSONDocument(
          "name" -> "bar2", "next" -> doc1))
      }
    }

    "be generated with @Flatten annotation" in {
      shapeless.test.illTyped("Macros.writer[InvalidRecursive]")
      shapeless.test.illTyped("Macros.writer[InvalidNonDoc]")

      val w = Macros.writer[LabelledRange]
      val lr = LabelledRange("range2", Range(start = 1, end = 3))
      val doc = BSONDocument("name" -> "range2", "start" -> 1, "end" -> 3)

      w.writeTry(lr) must beSuccessfulTry(doc)
    }
  }

  "DocumentClass" should {
    import reactivemongo.api.bson.DocumentClass

    shapeless.test.illTyped("implicitly[DocumentClass[Int]]")
    shapeless.test.illTyped("implicitly[DocumentClass[BSONValue]]")
    shapeless.test.illTyped("implicitly[DocumentClass[BSONDateTime]]")
    shapeless.test.illTyped("implicitly[DocumentClass[BSONLong]]")

    "be proved for case class Person" in {
      implicitly[DocumentClass[Person]] must not(beNull)
    }

    "be proved for sealed trait UT" in {
      implicitly[DocumentClass[Union.UT]] must not(beNull)
    }

    "be proved for BSONDocument" in {
      implicitly[DocumentClass[BSONDocument]] must not(beNull)
    }
  }

  // ---

  def roundtrip[A](original: A)(implicit reader: BSONReader[A], writer: BSONWriter[A]): MatchResult[Any] = {
    def serialized = writer writeTry original
    def deserialized = serialized.flatMap(reader.readTry(_))

    deserialized must beSuccessfulTry(original)
  }

  def roundtrip[A](original: A, format: BSONReader[A] with BSONWriter[A]): MatchResult[Any] = roundtrip(original)(format, format)

  def roundtripImp[A](data: A)(implicit reader: BSONReader[A], writer: BSONWriter[A]) = roundtrip(data)

}
