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

println(bytes.drop(4).take(15).decodeAsciiLenient)
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
def dumpHexAndAscii(bs: ByteVector): Unit =
  val str = bs.
    grouped(16).
    map(line => line.toHex + " " + line.decodeAsciiLenient).
    mkString("\n")
  println(str)
```

This implementation has a problem though -- the decoded ASCII often has non-printable characters, e.g. tabs, carriage returns, backspaces. We need to replace the non-printable characters with a placeholder character.

```scala mdoc
val NonPrintablePattern = "[^�\\p{Print}]".r

def printable(s: String): String = 
  NonPrintablePattern.replaceAllIn(s, ".")

def dumpHexAndPrintableAscii(bs: ByteVector): Unit =
  val str = bs.
    grouped(16).
    map { line => 
      line.toHex + " " + printable(line.decodeAsciiLenient)
    }.
    mkString("\n")
  println(str)

dumpHexAndPrintableAscii(bytes)
```

This is close, but the ASCII column in the last line is not aligned with the previous lines. Let's fix that while also adding an address column at the start of each line, specifying the index of the byte the line starts with.

```scala mdoc
def dumpHex(bs: ByteVector): Unit =
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

dumpHex(bytes)
```

With this new `dumpHex` routine, let's look at a couple more examples of pickling.

```scala
val p = Point(7, 8, 9)
val bytesPoint = ByteVector.view(p.pickle.value)
```
```scala mdoc:invisible
case class Point(x: Int, y: Int, z: Int)
case class Line(start: Point, end: Point)
val bytesPoint = hex"0000000f6d797061636b6167652e506f696e74000000070000000800000009"
```
```scala mdoc
dumpHex(bytesPoint)
```

This time, we can see the fully qualified class name of `Point` appear in the pickled output, unlike when we pickled `Line`, which only included the fully qualified class name of `Line`.

Let's also look at the first 4 bytes -- `0x0000000f` -- or 15 in decimal. This is the length of the string `mypackage.Point`. In the pickled output of `Line` from earlier, we similarly see the first 4 bytes are `0x0000000e`, or 14 decimal, which is the length of the string `mypackage.Line`.

There are two reasonable guesses we can make here about the Scala Pickling binary format:
* Strings are encoded as a 4-byte integer, specifying the number of subsequent bytes to read, followed by a UTF-8 (or perhaps ASCII) encoding of the string.
* Pickling an object results in the fully qualified class name of the object being encoded as a string.

The scodec library provides a built-in codec for UTF-8 strings prefixed by a 32-bit integer size field:

```scala mdoc
import scodec.codecs._

println(utf8_32.encode("Hello, world!"))
```

The `utf8_32` codec is an alias for `variableSizeBytes(int32, utf8)` -- The `variableSizeBytes` operation builds a codec from a size codec and a value code. The size codec specifies the format of the size of the value in bytes and the value codec subsequently only sees the specified number of bytes from the input.

When we decode a pickled value, we don't really want to do anything with the resulting string that specifies a class name. Rather, we want to write a codec for a specific class -- e.g. `Point` or `Line` -- where we know the input should start with the fully qualified class name. Let's define this.

```scala mdoc
import scodec.Codec

def constantString(s: String): Codec[Unit] =
  utf8_32.unit(s)

val helloWorld = constantString("Hello, world!")

println(helloWorld.encode(()))
```

Looking back at the pickled version of `Point`, we see that after the fully qualified class name, the 3 component integers are encoded sequentially as 32-bit big endian values. We can build a codec for this format using scodec's built-in `int32` codec three successive times.

```scala mdoc
val pointComponents: Codec[(Int, Int, Int)] = int32 :: int32 :: int32

println(pointComponents.encode(1, 2, 3))
```

We can combine this with `constantString` to build a codec for `Point`:

```scala mdoc
val pointCodec =
  (constantString("mypackage.Point") ~> int32 :: int32 :: int32).as[Point]

println(pointCodec.decode(bytesPoint.bits))
```

And this same codec can generate binary output that's readable by Scala Pickling:

```scala mdoc
println(pointCodec.encode(Point(7, 8, 9)))
```