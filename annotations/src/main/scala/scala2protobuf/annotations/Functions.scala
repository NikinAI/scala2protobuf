package scala2protobuf.annotations

class FunctionData extends scala.annotation.StaticAnnotation
class ServiceProcessor extends scala.annotation.StaticAnnotation

object Annotations {
  val serviceProcessorName = classOf[ServiceProcessor].getSimpleName
  val functionDataName = classOf[FunctionData].getSimpleName
}