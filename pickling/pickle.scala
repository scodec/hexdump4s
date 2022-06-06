package mypackage

import scala.pickling.Defaults._
import scala.pickling.binary._
import scodec.bits.ByteVector

case class Point(x: Int, y: Int, z: Int)
case class Line(start: Point, end: Point)

case class State(lines: Vector[Line])

object Main extends App {
  val p = Line(Point(1, 2, 3), Point(4, 5, 6))
  val bytes = ByteVector.view(p.pickle.value)
  println(bytes.toHex)

  val p2 = Point(7, 8, 9)
  val bytes2 = ByteVector.view(p2.pickle.value)
  println(bytes2.toHex)

  val s = State(Vector(
    Line(Point(1, 2, 3), Point(4, 5, 6)),
    Line(Point(7, 8, 9), Point(10, 11, 12))
  ))
  val bytes3 = ByteVector.view(s.pickle.value)
  println(bytes3.toHex)
}