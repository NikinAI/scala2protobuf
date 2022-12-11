package scala2protobuf

import java.io.File
import sbt.io._
import scala2protobuf.annotations.Annotations
import scala2protobuf.descriptor.scala.{Enum, Field, Message, Method, ScalaDescriptor, ScalaFile, ScalaPackage, ScalaType, Service}
import scala2protobuf.descriptor.{ConvertHelper, protobuf}

import scala.collection.parallel.{ParIterable, ParSeq}
import scala.meta
import scala.meta.inputs.Input
import scala.meta.parsers.Parse
import scala.meta._

object Scala2Protobuf {
  def apply(dialect: Dialect = dialects.Scala212): Scala2Protobuf =
    new Scala2Protobuf(dialect)
}

class Scala2Protobuf(dialect: Dialect) {

  def generate(input: Seq[File]): ParIterable[protobuf.File] = {
    generateInternal(
      input.par
        .map(
          file =>
            ScalaFile(file.getName,
                      IO.read(file, IO.utf8),
                      file.lastModified,
                      file.getPath)))
  }

  private[scala2protobuf] def generateInternal(
      files: ParSeq[ScalaFile]): ParIterable[protobuf.File] = {
    files
      .map { file =>
        (Parse.parseSource(Input.String(file.contents), dialect).get, file)
      }
      .flatMap {
        case (source, file) =>
          collectScalaDescriptor(ScalaPackage(""), source.stats, file)
      }
      .groupBy(_.pkg)
      .map {
        case (pkg, scalaDescriptors) =>
          toProtobufDescriptor(pkg,
                               scalaDescriptors.map(_.file.lastModified).max,
                               scalaDescriptors.seq)
      }
  }

  private[scala2protobuf] def collectScalaDescriptor(
      scalaPackage: ScalaPackage,
      stats: Seq[Stat],
      file: ScalaFile): Seq[ScalaDescriptor] = {
    stats.collect {
      case Pkg(pkg, pkgStats) =>
        collectScalaDescriptor(ScalaPackage(pkg.syntax.trim), pkgStats.collect {
          case s: Stat => s
        }, file)
      case obj: Pkg.Object =>
        val basePackage =
          if (scalaPackage.name.isEmpty) "" else scalaPackage.name + "."
        collectScalaDescriptor(ScalaPackage(basePackage + obj.name.value),
                               obj.templ.children.collect {
                                 case s: Stat => s
                               },
                               file)

      case clazz: Defn.Class if isCaseClass(clazz) && clazz.mods.collect{
        case a @ Mod.Annot(Init(Type.Name(Annotations.functionDataName),_, _)) => a
      }.nonEmpty =>
        Seq(toMessage(scalaPackage, clazz, file))
      case trt: Defn.Trait if isSealedTrait(trt) =>
        Seq(toEnum(scalaPackage, trt, stats, file))
      case trt: Defn.Trait if isProcessTrait(trt) => Seq(toService(scalaPackage, trt, file))
      case obj: Defn.Object if(isProcessTrait(obj)) => Seq(toService(scalaPackage, obj, file))
      case obj :Defn.Object =>     val basePackage =
        if (scalaPackage.name.isEmpty) "" else scalaPackage.name + "."
        collectScalaDescriptor(ScalaPackage(basePackage + obj.name.value),
          obj.templ.children.collect {
            case s: Stat => s
          },
          file)
    }.flatten
  }

  private[scala2protobuf] def isCaseClass(clazz: Defn.Class): Boolean =
    clazz.mods.exists {
      case _: Mod.Case => true
      case _ => false
    }

  private[scala2protobuf] def isProcessTrait(defn: Defn): Boolean = {
    defn match {
      case obj: Defn.Object => obj.mods.collect{
        case Mod.Annot(x) if x.tpe.toString() == "OurProcess" => true
        case _ => false
      }.nonEmpty
      case trt: Defn.Trait => trt.mods.collect{
        case Mod.Annot(x) if x.tpe.toString() == "OurProcess" => true
        case _ => false
      }.nonEmpty
      case _ => false
    }
  }

  private[scala2protobuf] def isSealedTrait(trt: Defn.Trait): Boolean =
    trt.mods.exists {
      case _: Mod.Sealed => true
      case _ => false
    }

  private[scala2protobuf] def toMessage(scalaPackage: ScalaPackage,
                                        clazz: Defn.Class,
                                        file: ScalaFile): Message = {
    Message(scalaPackage,
            file,
            clazz.name.value,
            clazz.ctor.paramss.head.map(toField))
  }

  private[scala2protobuf] def toField(param: Term.Param): Field = {
    Types.of(param.decltpe.get) match {
      case Types.Single(t) =>
        Field(isOptional = false,
              isRepeated = false,
              tpe = t,
              name = param.name.syntax)
      case Types.Option(Types.Single(t)) =>
        Field(isOptional = true,
              isRepeated = false,
              tpe = t,
              name = param.name.value)
      case Types.Seq(Types.Single(t)) =>
        Field(isOptional = false,
              isRepeated = true,
              tpe = t,
              name = param.name.syntax)
      case _ =>
        throw new RuntimeException(
          s"${param.decltpe.get} can not be used for the field type of Message")
    }
  }

  private[scala2protobuf] def toEnum(scalaPackage: ScalaPackage,
                                     trt: Defn.Trait,
                                     stats: Seq[Stat],
                                     file: ScalaFile): Enum = {
    def findValues(_stats: Seq[Stat]): Seq[String] =
      _stats.collect {
        case obj: Defn.Object
            if obj.templ.inits.exists(_.toString.trim == trt.name.value) =>
          Seq(obj.name.value)
        case obj: Defn.Object =>
          findValues(obj.templ.stats)
      }.flatten

    val values = findValues(stats)

    Enum(scalaPackage, file, trt.name.value, values)
  }

  private[scala2protobuf] def toService(scalaPackage: ScalaPackage,
                                        trt: Defn.Trait,
                                        file: ScalaFile): Service = {
    Service(scalaPackage, file, trt.name.value, trt.templ.stats.collect {
      case scalaMethod: Decl.Def => toMethod(scalaMethod)
    })
  }

  private[scala2protobuf] def toService(scalaPackage: ScalaPackage,
                                        trt: Defn.Object,
                                        file: ScalaFile): Service = {
    val defFunc = trt match {
      case  Defn.Object(List(
        Mod.Annot(Init(Type.Name(Annotations.serviceProcessorName),_, _)),_
      ), _, Template(_, List(Init(Type.Apply(_, List(input, _, output)), _, _)) , _, _ )) =>
        Decl.Def(
          List.empty,
          Term.Name("process"),
          List.empty,
          List(
            List(
              Term.Param(List.empty, Term.Name("input"), Some(input), None),
            )
          ),
          Type.Apply(Type.Name("Future"), List(output))
        )
    }

    Service(scalaPackage, file, trt.name.value, List(toMethod(defFunc)))
  }

  private[scala2protobuf] def toMethod(scalaMethod: Decl.Def): Method = {
    val inputParam = scalaMethod.paramss.flatten.headOption.getOrElse(
      throw new RuntimeException(s"Input parameter is missing")
    )
    if (scalaMethod.paramss.flatten.size > 1) {
      throw new RuntimeException(s"Must be only one parameter")
    }
    val methodName = scalaMethod.name.syntax.capitalize

    (Types.of(inputParam.decltpe.get), Types.of(scalaMethod.decltpe)) match {
      case (Types.Single(in), Types.Future(Types.Single(out))) =>
        Method(name = methodName,
               isStreamInput = false,
               inputType = in,
               isStreamOutput = false,
               outputType = out)
      case (Types.StreamObserver(Types.Single(in)),
            Types.Future(Types.Single(out))) =>
        Method(name = methodName,
               isStreamInput = true,
               inputType = in,
               isStreamOutput = false,
               outputType = out)
      case (Types.Single(in), Types.StreamObserver(Types.Single(out))) =>
        Method(name = methodName,
               isStreamInput = false,
               inputType = in,
               isStreamOutput = true,
               outputType = out)
      case (Types.StreamObserver(Types.Single(in)),
            Types.StreamObserver(Types.Single(out))) =>
        Method(name = methodName,
               isStreamInput = true,
               inputType = in,
               isStreamOutput = true,
               outputType = out)
      case _ =>
        throw new RuntimeException(
          s"${inputParam.decltpe.get} => ${scalaMethod.decltpe} can not be used for service type ")
    }
  }

  private[scala2protobuf] def toMethod(scalaMethod: meta.Defn.Def): Method = {
    val inputParam = scalaMethod.paramss.flatten.headOption.getOrElse(
      throw new RuntimeException(s"Input parameter is missing")
    )
    if (scalaMethod.paramss.flatten.size > 1) {
      throw new RuntimeException(s"Must be only one parameter")
    }
    val methodName = scalaMethod.name.syntax.capitalize

    (Types.of(inputParam.decltpe.get), Types.of(scalaMethod.decltpe.get)) match {
      case (Types.Single(in), Types.Future(Types.Single(out))) =>
        Method(name = methodName,
          isStreamInput = false,
          inputType = in,
          isStreamOutput = false,
          outputType = out)
      case (Types.StreamObserver(Types.Single(in)),
      Types.Future(Types.Single(out))) =>
        Method(name = methodName,
          isStreamInput = true,
          inputType = in,
          isStreamOutput = false,
          outputType = out)
      case (Types.Single(in), Types.StreamObserver(Types.Single(out))) =>
        Method(name = methodName,
          isStreamInput = false,
          inputType = in,
          isStreamOutput = true,
          outputType = out)
      case (Types.StreamObserver(Types.Single(in)),
      Types.StreamObserver(Types.Single(out))) =>
        Method(name = methodName,
          isStreamInput = true,
          inputType = in,
          isStreamOutput = true,
          outputType = out)
      case _ =>
        throw new RuntimeException(
          s"${inputParam.decltpe.get} => ${scalaMethod.decltpe} can not be used for service type ")
    }
  }

  private[scala2protobuf] def toProtobufDescriptor(
      pkg: ScalaPackage,
      lastModified: Long,
      scalaDescriptors: Seq[ScalaDescriptor]): protobuf.File = {

    val messages = scalaDescriptors.collect {
      case Message(_, _, messageName, fields: Seq[Field]) =>
        protobuf.Message(
          messageName,
          fields.zipWithIndex.map {
            case (Field(isOptional, isRepeated, tpe: ScalaType, name), index) =>
              protobuf.Field(isOptional,
                             isRepeated,
                             tpe.protobufType,
                             name,
                             index + 1)
          }
        )
    }

    val services = scalaDescriptors.collect {
      case Service(_, _, serviceName, methods: Seq[Method]) =>
        protobuf.Service(
          serviceName,
          methods.map {
            case Method(name,
                        isStreamInput,
                        inputType: ScalaType,
                        isStreamOutput,
                        outputType: ScalaType) =>
              protobuf.Method(name,
                              isStreamInput,
                              inputType.protobufType,
                              isStreamOutput,
                              outputType.protobufType)
          }
        )
    }

    val enums = scalaDescriptors.collect {
      case Enum(_, _, enumName, values) =>
        protobuf.Enum(
          enumName,
          values
        )
    }

    protobuf.File(
      ConvertHelper.defaultFileNameConverter(pkg.name),
      protobuf.Syntax.PROTO3,
      ConvertHelper.defaultPackageConverter(pkg.name),
      ConvertHelper.defaultFileOptionConverter(pkg.name),
      messages,
      services,
      enums,
      lastModified
    )
  }

}
