# hexdump4s

*INCOMPLETE - WORK IN PROGRESS*

I recently encountered a distributed application that stored some cached state in DynamoDB. The application used [scala-pickling](https://github.com/scala/pickling) to encode/decode its state to/from binary. Unfortunately, scala-pickling is no longer maintained and was last released for Scala 2.11. Resultantly, this application was stuck on Scala 2.11 with no upgrade path.

One approach to this problem is changing the binary format stored in DynamoDB to something well supported (e.g. protobuf) and clearing the cache at time of deployment. That comes with a significant disadvantage though -- we'd need the ability to gradually deploy a new application version to various nodes, which means for a period of time we'd have both the old and new versions of the application running against the same database.

Instead, we could store the new binary format in new tables while keeping the scala-pickling based format in the original tables. This would allow for rolling upgrades and rollbacks, but with the disadvantage of the cluster state being split between the old and new nodes.

For this particular application, neither of these options were desirable. Instead, we wanted to continue to read and write cluster state in the existing binary format while also upgrading to modern Scala releases. To do so, we decided to reverse engineer the scala-pickling binary format with [scodec](https://scodec.org). In the process, we added hex dump support to scodec and built a native application using [scala-cli](https://scala-cli.virtuslab.org). In this article, we'll explore how this was done.

# Scala Pickling

The Scala Pickling library supports compile time generation of binary encoders and decoders (JSON is also supported, though we're not using it here). Consider the type `Line`, which consists of two `Point`s:

```scala
package mypackage 

case class Point(x: Int, y: Int, z: Int)
case class Line(start: Point, end: Point)
```

To serialize a `Line` to binary, we can add a few imports from the `scala.pickling` package and call `.pickle` on an instance of `Line`:

```scala
import scala.pickling.Defaults._
import scala.pickling.binary._
import scodec.bits.ByteVector

val line = Line(Point(1, 2, 3), Point(4, 5, 6))
val bytes = ByteVector.view(line.pickle.value)
```

`line.pick.value` returns an `Array[Byte]`, which we wrap with a `scodec.bits.ByteVector`. We can inspect this byte vector in various ways:

```scala mdoc:invisible
import scodec.bits._
val bytes = hex"0000000e6d797061636b6167652e4c696e65fb000000010000000200000003fb000000040000000500000006"
```
```scala mdoc
println(bytes.toHex)

println(bytes.decodeAscii)

// println(bytes.decodeAsciiLenient)
```

Inpsecting the hex string provides some clues about how Scala Pickling has serialized the `Line` and `Point` types. The right half of the vector shows the integers 1, 2, 3 and then 4, 5, 6, and there's a `0xfb` character preceeding each triple. The left half of the vector has a dense section of bytes mostly in the `0x50-0x80` range. Decoding the whole vector as ASCII fails, but decoding it leniently, where unmappable charaters are replaced with `�`, shows that the fully qualified class name of `Line` is included.

We can do more experiments in a similar way -- e.g., changing one of the paramters to one of our `Point` instances and comparing the hex output to the original output, confirming the change occurs where we expect. We may also want to use `drop` and `take` and other operations on `ByteVector` when exploring the example output. To assist with visual inspection of the results, let's define a utility function to print a hex dump with 16 bytes per line.

```scala mdoc
def dumpHexBasic(bs: ByteVector): Unit =
  println(bs.grouped(16).map(_.toHex).mkString("\n"))

dumpHexBasic(bytes)
```

The `grouped` operation does most of the heavy lifting here -- `grouped` converts a `ByteVector` in to an `Iterator[ByteVector]`, where each inner vector is the specified number of bytes, except the last vector which may be less.

Despite using scodec for nearly a decade, working with a wide variety of binary protocols, and writing variants of this function dozens of times, it doesn't exist directly in the library. We can add a variety of useful features to this function -- features that we wouldn't take the time to define in an adhoc debugging session but would be very useful.

For example, we can add another column to the output with the decoded ASCII of each line:

```scala mdoc
def dumpHexAndAscii(bs: ByteVector): Unit = {
  val str = bs.
    grouped(16).
    map(line => line.toHex + " " + line.decodeAsciiLenient).
    mkString("\n")
  println(str)
}
```

This implementation has a problem though -- the decoded ASCII often has non-printable characters, e.g. tabs, carriage returns, backspaces. We need to replace the non-printable characters with a placeholder character.

```scala mdoc
val NonPrintablePattern = "[^�\\p{Print}]".r

def printable(s: String): String = 
  NonPrintablePattern.replaceAllIn(s, ".")

def dumpHexAndPrintableAscii(bs: ByteVector): Unit = {
  val str = bs.
    grouped(16).
    map { line => 
      line.toHex + " " + printable(line.decodeAsciiLenient)
    }.
    mkString("\n")
  println(str)
}

dumpHexAndPrintableAscii(bytes)
```

This is close, but the ASCII column in the last line is not aligned with the previous lines. Let's fix that while also adding an address column at the start of each line, specifying the index of the byte the line starts with.

```scala mdoc
def dumpHex(bs: ByteVector): Unit = {
  val str = bs.
    grouped(16).
    zipWithIndex.
    map { case (line, idx) => 
      val address = ByteVector.fromInt(idx * 16).toHex
      val data = line.toHex
      val ascii = printable(line.decodeAsciiLenient)
      val padding = 2 * (16 - line.size.toInt) + 1
      address + " " + data + (" " * padding) + ascii
    }.
    mkString("\n")
  println(str)
}

dumpHex(bytes)
```