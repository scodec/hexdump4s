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
  constantString("mypackage.Point") ~> (int32 :: int32 :: int32).as[Point]

println(pointCodec.decode(bytesPoint.bits))
```

And this same codec can generate binary output that's readable by Scala Pickling:

```scala mdoc
println(pointCodec.encode(Point(7, 8, 9)))
```

Let's look back at the pickled `Line` example now:

```scala mdoc
dumpHex(bytes)
```

Before each of the points, there's a `0xfb` character, and there's no appearance of the fully qualified class name of `Point`. It seems that when Scala Pickling has enough context to know the type of the next field in the binary, it elides type information. A quick scan through the Scala Pickling source shows [some constant byte tags](https://github.com/scala/pickling/blob/f7c64bc11f11e78e80ff326da9fbc4fa8d045a80/core/src/main/scala/scala/pickling/binary/BinaryPickleFormat.scala#L32) and confirms `0xfb` is the elided tag.

```scala mdoc
val pointElidedCodec =
  constant(0xfb) ~> (int32 :: int32 :: int32).as[Point]

val lineCodec =
  constantString("mypackage.Line") ~> (pointElidedCodec :: pointElidedCodec).as[Line]

println(lineCodec.decode(bytes.bits))
```

Let's look at the binary format of one more type, `State`, which stores a list of lines:

```scala
case class State(lines: Vector[Line])

val s = State(Vector(
  Line(Point(1, 2, 3), Point(4, 5, 6)),
  Line(Point(7, 8, 9), Point(10, 11, 12))
))

val bytesState = ByteVector.view(s.pickle.value)
```
```scala mdoc:invisible
case class State(lines: List[Line])
val bytesState = hex"0000000f6d797061636b6167652e5374617465fb000000020000000e6d797061636b6167652e4c696e65fb000000010000000200000003fb0000000400000005000000060000000e6d797061636b6167652e4c696e65fb000000070000000800000009fb0000000a0000000b0000000c"
```
```scala mdoc
dumpHex(bytesState)
```

Here we see the expected `myPackage.State` string, followed by an elided type tag, then a 32-bit integer field specifying the number of elements in the vector. After the count field, there's two successive lines, both encoded with their fully qualified class name, not an elided type tag.

```scala mdoc
val stateCodec = constantString("mypackage.State") ~> constant(0xfb) ~> vectorOfN(int32, lineCodec)

println(stateCodec.decode(bytesState.bits))
```

Using `dumpHex`, we can continue to experiment with pickling more complex types and build up various codecs. For instance, serializing `Option` values, other collection types, and so on. For the rest of this article, we'll instead focus on improving `dumpHex`.

## Configurable hex dumps

Let's return to `dumpHex` and consider other features we might want. For inspiration, we can look at the [man page of Linux's hexdump](https://man7.org/linux/man-pages/man1/hexdump.1.html). What we've built so far loosely resembles the output of `hexdump -C`, with an address column, hex data column, and ASCII column. Let's support the following additional features:
- Improve formatting by using something similar to `hexdump`'s output.  Hex data will be organized in to 8 byte columns, with each byte separated by a space, and with each column separated by an additional space. We'll also add a border around the ASCII column.
- Support a configurable number of columns of data and number of bytes per column, using a default of 2 columns of 8 bytes each.
- Support suppressing the output of the address and/or ASCII column.
- Support customizing the hexadecimal alphabet (e.g. lowercase versus uppercase).

We could modify `dumpHex` to take a bunch of parameters with defaults, but this has a couple disadvantages. First, it makes it difficult to maintain binary compatibility if in a future version we want to add new configuration options. Second, it doesn't let us treat various combinations of settings as *values*. For instance, we may want to have a value that represents the default format and another that represents the format without the ASCII column. We can also use such values as starting points for futher customizations. We could represent a bunch of parameters with a case class, and various preconfigured parameter sets as values in the companion object of that case class. But we'd still have binary compatibility issues -- e.g., adding a parameter to the case class would not be binary compatible. Instead, the builder pattern aligns well with our requirements.

```scala
final class HexDumpFormat private (
    val includeAddressColumn: Boolean,
    val dataColumnCount: Int,
    val dataColumnWidthInBytes: Int,
    val includeAsciiColumn: Boolean,
    val alphabet: Bases.HexAlphabet
):

  def withIncludeAddressColumn(newIncludeAddressColumn: Boolean): HexDumpFormat =
    new HexDumpFormat(
      newIncludeAddressColumn,
      dataColumnCount,
      dataColumnWidthInBytes,
      includeAsciiColumn,
      alphabet
    )

  def withDataColumnCount(newDataColumnCount: Int): HexDumpFormat =
    new HexDumpFormat(
      includeAddressColumn,
      newDataColumnCount,
      dataColumnWidthInBytes,
      includeAsciiColumn,
      alphabet
    )

  // Additional builder methods for each parameter...

object HexDumpFormat:
  val Default: HexDumpFormat =
    new HexDumpFormat(true, 2, 8, true, Bases.Alphabets.HexLowercase)

  /** Like [[Default]] but with 3 columns of data and no ASCII column. */
  val NoAscii: HexDumpFormat =
    Default.withIncludeAsciiColumn(false).withDataColumnCount(3)
```

We can then add various methods to the `HexDumpFormat` class for rending a hex dump:

```scala
def render(bytes: ByteVector): String =
  val bldr = new StringBuilder
  render(bytes, line => { bldr.append(line); () })
  bldr.toString

def render(bytes: ByteVector, onLine: String => Unit): Unit =
  val numBytesPerLine = dataColumnWidthInBytes * dataColumnCount
  val bytesPerLine = bytes.grouped(numBytesPerLine.toLong)
  bytesPerLine.zipWithIndex.foreach { case (bytesInLine, index) =>
    val line = renderLine(bldr, bytesInLine, index * numBytesPerLine)
    onLine(line)
  }

def renderLine(bytes: ByteVector, address: Int): String =
  ???

def print(bytes: ByteVector): Unit =
  render(bytes, line => Console.print(line))
```

This probably seems like a strange collection of methods. Why not just a single `def render(bytes: ByteVector): String`? Most of the time we render a hex dump, we're immediately going to print it (or perhaps log it). If we only had a single `render` method that returned a string, then when rendering a large input vector, we'd have to accumulate the entire rendering in to a single string before printing any of the output. But printing can be done incrementally -- a line at a time. There's no need to accumulate all of those lines in memory before printing anything!

Now let's look at the implementation of `renderLine`:

```scala
def renderLine(bytes: ByteVector, address: Int): String =
  val bldr = new StringBuilder
  if includeAddressColumn then
    bldr
      .append(ByteVector.fromInt(address).toHex(alphabet))
      .append("  ")
  bytes.grouped(dataColumnWidthInBytes.toLong).foreach { columnBytes =>
    renderHex(bldr, columnBytes)
    bldr.append(" ")
  }
  if includeAsciiColumn then
    val padding =
      val bytesOnThisLine = bytes.size.toInt
      val dataBytePadding = (numBytesPerLine - bytesOnThisLine) * 3 - 1
      val numFullDataColumns = (bytesOnThisLine - 1) / dataColumnWidthInBytes
      val numAdditionalColumnSpacers = dataColumnCount - numFullDataColumns
      dataBytePadding + numAdditionalColumnSpacers
    bldr
      .append(" " * padding)
      .append('│')
      .append(printable(bytes.decodeAsciiLenient))
      .append('│')
  bldr
    .append('\n')
    .toString

def renderHex(bldr: StringBuilder, bytes: ByteVector): Unit =
  bytes.foreach { b =>
    bldr
      .append(alphabet.toChar((b >> 4 & 0x0f).toByte.toInt))
      .append(alphabet.toChar((b & 0x0f).toByte.toInt))
      .append(' ')
    ()
  }
```

There's a lot of code here, though it's mostly straightforward. We create a `StringBuilder` for the line, append the various columns, and return a single string. The input `bytes` parameter only consists of the bytes for this line (16 by default). The implementation groups these bytes by the number of bytes per column, resulting in a sub-vector for each data column. The most complicated bit is calculating the number of padding characters between the final byte and the start of the ASCII column, in the case where the byte length is not evenly divisible by the number of bytes per line.

We also needed to define `renderHex` instead of using the built-in `toHex` on `ByteVector` as a result of adding a space after each byte.

## Colorized hex dumps

Most of the time, hex dumps get printed to the console. We can improve that experience by adding color to the output via [ANSI escape codes](https://en.wikipedia.org/wiki/ANSI_escape_code). We'll need to conditionally enable/disable ANSI in the output in order to support use cases like writing hex dumps to logs or working with terminals that don't have ANSI support, so we let's add an `ansiEnabled` flag to `HexDumpFormat` and modify our rendering functions to use ANSI where appropriate.

For starters, let's de-emphasize both the address column and unmappable / non-printable characters in the ASCII column, which will bring more visual attention to the data. We can use the "faint" attribute of the ANSI Select Graphic Rendition parameter set to accomplish this. To enable the faint attribute, we must output the sequence `"\u001b[;2m"`. Then to disable the faint attribute and restore the attributes to normal, we must output the sequence `"\u001b[;22m"`.

Enabling the faint attribute on the address column is then accomplished by simply writing the faint sequence prior to writing the address and then writing the reset sequence afterwards:

```scala
object Ansi:
  val Faint = "\u001b[;2m"
  val Normal = "\u001b[;22m"

def renderLine(bytes: ByteVector, address: Int): String =
  val bldr = new StringBuilder
  if includeAddressColumn then
    if ansiEnabled then bldr.append(Ansi.Faint)
    bldr.append(ByteVector.fromInt(address).toHex(alphabet))
    if ansiEnabled then bldr.append(Ansi.Normal)
    bldr.append("  ")
  ...
```

Rendering unmappable and non-printable ASCII faintly can be accomplished with some regular expression replacements:

```scala
val FaintDot = s"${Ansi.Faint}.${Ansi.Normal}"
val FaintUnmappable = s"${Ansi.Faint}�${Ansi.Normal}"

def renderAsciiBestEffort(bldr: StringBuilder, bytes: ByteVector): Unit =
  val decoded = bytes.decodeAsciiLenient
  val nonPrintableReplacement = if ansiEnabled then FaintDot else "."
  val printable = NonPrintablePattern.replaceAllIn(decoded, nonPrintableReplacement)
  val colorized = if ansiEnabled then printable.replaceAll("�", FaintUnmappable) else printable
  bldr.append(colorized)
  ()
```

How about coloring the hex data? Is there any value to doing so? If we color the hex values based on a color gradient, where the magnitude of difference between two values is represented with a scaled magnitude in color change, then glancing at a colorized hex dump can assist in detecting patterns in the data.

ANSI supports both an [8-bit color palette](https://en.wikipedia.org/wiki/ANSI_escape_code#8-bit) and [24-bit RGB color](https://en.wikipedia.org/wiki/ANSI_escape_code#24-bit), though not all terminals support 24-bit color (e.g. OS X's Terminal.app). For this application, we'll use 24-bit color.

We'll need to modify `renderHex` to output an ANSI escape sequence that sets the foreground color to an RGB value prior to each byte. Then, in `renderLine`, after all the bytes in a line have been printed, we'll need to reset the foreground color to the default via another ANSI escape sequence.

```scala
object Ansi:
  val Reset = "\u001b[0m"
  def foregroundColor(bldr: StringBuilder, rgb: (Int, Int, Int)): Unit = {
    bldr
      .append("\u001b[38;2;")
      .append(rgb._1)
      .append(";")
      .append(rgb._2)
      .append(";")
      .append(rgb._3)
      .append("m")
    ()
 
def renderHex(bldr: StringBuilder, bytes: ByteVector): Unit =
  bytes.foreach { b =>
    if ansiEnabled then Ansi.foregroundColor(bldr, rgbForByte(b))
    bldr
      .append(alphabet.toChar((b >> 4 & 0x0f).toByte.toInt))
      .append(alphabet.toChar((b & 0x0f).toByte.toInt))
      .append(' ')
    ()
  }

def rgbForByte(b: Byte): (Int, Int, Int) = ???
```

How do we define `rgbForByte`? We need a function which maps 0-255 on to a color space, such that close values have close colors and distant values have distant colors. The [Hue, Saturation, Value (HSV)](https://en.wikipedia.org/wiki/HSL_and_HSV) color space turns this problem in to a simple linear interpolation of the hue. We pick a fixed saturation and value (based on aesthetic preference) and then interpolate the byte value (0-255) over the domain of the hue (0-360 degrees). ANSI doesn't support HSV color though, so we'll also need a way to [convert an HSV color to the equivalent in RGB](https://en.wikipedia.org/wiki/HSL_and_HSV#HSV_to_RGB).

```scala
def rgbForByte(b: Byte): (Int, Int, Int) =
  val saturation = 0.4
  val value = 0.75
  val hue = ((b & 0xff) / 256.0) * 360.0
  hsvToRgb(hue, saturation, value)

/** Converts HSV color to RGB. Hue is 0-360, saturation/value are 0-1. */
def hsvToRgb(hue: Double, saturation: Double, value: Double): (Int, Int, Int) =
  val c = saturation * value
  val h = hue / 60
  val x = c * (1 - (h % 2 - 1).abs)
  val z = 0d
  val (r1, g1, b1) = h.toInt match
    case 0 => (c, x, z)
    case 1 => (x, c, z)
    case 2 => (z, c, x)
    case 3 => (z, x, c)
    case 4 => (x, z, c)
    case 5 => (c, z, x)
  val m = value - c
  val (r, g, b) = (r1 + m, g1 + m, b1 + m)
  def scale(v: Double) = (v * 256).toInt
  (scale(r), scale(g), scale(b))
```

Assuming we've added a method directly to `ByteVector` that prints a hex dump using the default `HexDumpFormat`, and the default format enables ANSI output, running this:

```scala
ByteVector(0 until 256: _*).printHexDump()
```

Produces this output:

![Colorized output of bytes 0 through 255](images/hexdump-all-bytes.png)

And a colorized version of the pickled version of `State` from earlier renders as:

![Colorized output of pickled State object](images/hexdump-state.png)

## Building a command line app

TODO
## Scala Native

TODO
## Streaming

TODO

TODO: Why the `BitVector` variants though? `BitVector` supports *suspended construction* -- i.e., constructing the vector as it is traversed instead of constructing it all at once. Operations like `BitVector.fromInputStream` take advantage of this to avoid loading the entire input. However, when a `BitVector` is converted to a `ByteVector`, the suspensions are forced, resulting in all data being loaded in to memory. By implementing the main algorithm in terms of `BitVector`, we avoid forcing the entire computation.