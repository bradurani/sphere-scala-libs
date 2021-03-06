package io.sphere.mongo

import scala.annotation.meta.getter
import scala.reflect.{ classTag, ClassTag }
import scala.language.experimental.macros

import io.sphere.mongo.format.MongoFormat
import io.sphere.mongo.format._
import io.sphere.util.{ Logging, Reflect, Memoizer }

import com.mongodb._

/**
  * copy/paste from https://github.com/sphereio/sphere-scala-libs/blob/master/json/src/main/scala/generic/package.fmpp.scala
  * adapted to `MongoFormat`.
  */
package object generic extends Logging {

  type MongoEmbedded = io.sphere.mongo.generic.annotations.MongoEmbedded @getter
  type MongoKey = io.sphere.mongo.generic.annotations.MongoKey @getter
  type MongoIgnore = io.sphere.mongo.generic.annotations.MongoIgnore @getter
  type MongoTypeHint = io.sphere.mongo.generic.annotations.MongoTypeHint
  type MongoTypeHintField = io.sphere.mongo.generic.annotations.MongoTypeHintField

  def deriveMongoFormat[A]: MongoFormat[A] = macro MongoFormatMacros.deriveMongoFormat_impl[A]

  /** The default name of the field used for type-hinting, taken from the MongoTypeHintField annotation. */
  val defaultTypeFieldName: String = classOf[MongoTypeHintField].getMethod("value").getDefaultValue.asInstanceOf[String]

  <#list 1..22 as i>
  <#assign typeParams><#list 1..i as j>A${j}<#if i !=j>,</#if></#list></#assign>
  <#assign implTypeParams><#list 1..i as j>A${j} : MongoFormat<#if i !=j>,</#if></#list></#assign>
  /** Creates a `MongoFormat[T]` instance for a product type (case class) `T` of arity ${i}. */
  def mongoProduct[T <: Product: ClassTag, ${implTypeParams}](
    construct: <#list 1..i as j>A${j}<#if i !=j> => </#if></#list> => T
  ): MongoFormat[T] = {
    val mongoClass = getMongoClassMeta(classTag[T].runtimeClass)
    val fields = mongoClass.fields
    new MongoFormat[T] {
      def toMongoValue(r: T): Any = {
        val dbo = new BasicDBObject
        if (mongoClass.typeHint.isDefined) {
          val th = mongoClass.typeHint.get
          dbo.put(th.field, th.value)
        }
        <#list 1..i as j>
          writeField[A${j}](dbo, fields(${j-1}), r.productElement(${j-1}).asInstanceOf[A${j}])
        </#list>
        dbo
      }
      def fromMongoValue(any: Any): T = any match {
        case dbo: DBObject =>
          construct<#list 1..i as j>(
            readField[A${j}](fields(${j-1}), dbo))</#list>
        case _ => sys.error("Deserialization failed. DBObject expected.")
      }
    }
  }
  </#list>


  /** Derives a `MongoFormat[T]` instance for some supertype `T`. The instance acts as a type-switch
    * for the subtypes `A1` and `A2`, delegating to their respective MongoFormat instances based
    * on a field that acts as a type hint. */
  def mongoTypeSwitch[T: ClassTag, A1 <: T: ClassTag: MongoFormat, A2 <: T: ClassTag: MongoFormat](selectors: List[TypeSelector[_]]): MongoFormat[T] = {
    val allSelectors = typeSelector[A1] :: typeSelector[A2] :: selectors
    val readMapBuilder = Map.newBuilder[String, TypeSelector[_]]
    val writeMapBuilder = Map.newBuilder[Class[_], TypeSelector[_]]
    allSelectors.foreach { s =>
      readMapBuilder += (s.typeValue -> s)
      writeMapBuilder += (s.clazz -> s)
    }
    val readMap = readMapBuilder.result
    val writeMap = writeMapBuilder.result
    val clazz = classTag[T].runtimeClass

    val typeField = Option(clazz.getAnnotation(classOf[MongoTypeHintField])) match {
      case Some(a) => a.value
      case None => defaultTypeFieldName
    }

    new MongoFormat[T] {
      def fromMongoValue(any: Any): T = any match {
        case dbo: DBObject =>
          findTypeValue(dbo, typeField) match {
            case Some(t) => readMap.get(t) match {
              case Some(r) => r.read(dbo).asInstanceOf[T]
              case None => sys.error("Invalid type value '" + t + "' in DBObject '%s'.".format(dbo))
            }
            case None => sys.error("Missing type field '" + typeField + "' in DBObject '%s'.".format(dbo))
          }
        case _ => sys.error("DBObject expected.")
      }
      def toMongoValue(t: T): Any = writeMap.get(t.getClass) match {
        case Some(w) => w.write(t) match {
          case dbo: DBObject => findTypeValue(dbo, w.typeField) match {
            case Some(_) => dbo
            case None => dbo.put(w.typeField, w.typeValue)
          }
        }
        case None => new BasicDBObject(defaultTypeFieldName, defaultTypeValue(t.getClass))
      }
    }
  }

  <#list 3..80 as i>
  <#assign typeParams><#list 1..i-1 as j>A${j}<#if i-1 != j>,</#if></#list></#assign>
  <#assign implTypeParams><#list 1..i as j>A${j} <: T : MongoFormat : ClassTag<#if i !=j>,</#if></#list></#assign>
  def mongoTypeSwitch[T: ClassTag, ${implTypeParams}](selectors: List[TypeSelector[_]]): MongoFormat[T] = mongoTypeSwitch[T, ${typeParams}](typeSelector[A${i}] :: selectors)
  </#list>

  final class TypeSelector[A: MongoFormat] private[mongo](val typeField: String, val typeValue: String, val clazz: Class[_]) {
    def read(any: Any): A = fromMongo[A](any)
    def write(a: Any): Any = toMongo[A](a.asInstanceOf[A])
  }

  private case class MongoClassMeta(typeHint: Option[MongoClassMeta.TypeHint], fields: IndexedSeq[MongoFieldMeta])
  private object MongoClassMeta {
    case class TypeHint(field: String, value: String)
  }
  private case class MongoFieldMeta(
    name: String,
    default: Option[Any] = None,
    embedded: Boolean = false,
    ignored: Boolean = false
  )

  private val getMongoClassMeta = new Memoizer[Class[_], MongoClassMeta](clazz => {
    def hintVal(h: MongoTypeHint): String =
      if (h.value.isEmpty) defaultTypeValue(clazz)
      else h.value

    log.trace("Initializing Mongo metadata for %s".format(clazz.getName))

    val typeHintFieldAnnot = clazz.getAnnotation(classOf[MongoTypeHintField])
    val typeHintAnnot = clazz.getAnnotation(classOf[MongoTypeHint])
    val typeField = Option(typeHintFieldAnnot).map(_.value)
    val typeValue = Option(typeHintAnnot).map(hintVal)

    MongoClassMeta(
      typeHint = (typeField, typeValue) match {
        case (Some(field), Some(hint)) => Some(MongoClassMeta.TypeHint(field, hint))
        case (None       , Some(hint)) => Some(MongoClassMeta.TypeHint(defaultTypeFieldName, hint))
        case (Some(field), None)       => Some(MongoClassMeta.TypeHint(field, defaultTypeValue(clazz)))
        case (None       , None)       => None
      },
      fields = getMongoFieldMeta(clazz)
    )
  })

  private def getMongoFieldMeta(clazz: Class[_]): IndexedSeq[MongoFieldMeta] = {
    Reflect.getCaseClassMeta(clazz).fields.map { fm =>
      val m = clazz.getDeclaredMethod(fm.name)
      val name = Option(m.getAnnotation(classOf[MongoKey])).map(_.value).getOrElse(fm.name)
      val embedded = m.isAnnotationPresent(classOf[MongoEmbedded])
      val ignored = m.isAnnotationPresent(classOf[MongoIgnore])
      if (ignored && fm.default.isEmpty) {
        throw new Exception("Ignored Mongo field '%s' must have a default value.".format(fm.name))
      }
      MongoFieldMeta(name, fm.default, embedded, ignored)
    }
  }

  private def writeField[A: MongoFormat](dbo: DBObject, field: MongoFieldMeta, e: A): Unit =
    if (!field.ignored) {
      if (field.embedded)
        toMongo(e) match {
          case dbo2: DBObject => dbo.putAll(dbo2)
          case MongoNothing => ()
          case x => dbo.put(field.name, x)
        }
      else
        toMongo(e) match {
          case MongoNothing => ()
          case x => dbo.put(field.name, x)
        }

    }

  private def readField[A: MongoFormat](f: MongoFieldMeta, dbo: DBObject): A = {
    val mf = MongoFormat[A]
    if (f.ignored)
      f.default.asInstanceOf[Option[A]].orElse(mf.default).getOrElse {
        throw new Exception("Missing default for ignored field.")
      }
    else if (f.embedded) mf.fromMongoValue(dbo)
    else {
      val value = dbo.get(f.name)
      if (value != null) mf.fromMongoValue(value)
      else mf.default.getOrElse {
        throw new Exception("Missing required field '%s' on deserialization.".format(f.name))
      }
    }
  }

  private def findTypeValue(dbo: DBObject, typeField: String): Option[String] =
    Option(dbo.get(typeField)).map(_.toString)

  private def typeSelector[A: ClassTag: MongoFormat](): TypeSelector[_] = {
    val clazz = classTag[A].runtimeClass
    val (typeField, typeValue) = getMongoClassMeta(clazz).typeHint match {
      case Some(hint) => (hint.field, hint.value)
      case None => (defaultTypeFieldName, defaultTypeValue(clazz))
    }
    new TypeSelector[A](typeField, typeValue, clazz)
  }

  private def defaultTypeValue(clazz: Class[_]): String =
    clazz.getSimpleName.replace("$", "")
}