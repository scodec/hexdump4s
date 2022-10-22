//> using scala "3.2.0"
//> using lib "org.scodec::scodec-bits::1.1.34"
//> using lib "com.monovore::decline::2.3.1"
//
// Build JVM distribution with: scala-cli package hexdump4s.sc -o hexdump4s -f --assembly
// Build Scala Native version with: scala-cli package --native hexdump4s.sc -o hexdump4s -f
// Build GraalVM native image version with: scala-cli package --native-image hexdump4s.sc -f -- --no-fallback
//
import scodec.bits.*
import com.monovore.decline.*
import java.nio.file.{Files, Path}
import cats.syntax.all.*

val command = Command(
  name = "hexdump4s",
  header = "Prints a hex dump of a file"
) {
  val offset = Opts.option[Long]("offset", short = "s", metavar = "count",
    help = "Number of bytes to skip at start of input").withDefault(0L)
  val length = Opts.option[Long]("length", short = "n", metavar = "count",
    help = "Number of bytes to dump").orNone
  val noColor = Opts.flag("no-color", help = "Disables color ouptut").orFalse
  val file = Opts.argument[Path](metavar = "file").orNone
  (offset, length, noColor, file).tupled
}

command.parse(args) match
  case Left(help) =>
    System.err.println(help)
  case Right((offset, limit, noColor, file)) =>
    def data: BitVector =
      val source = BitVector.fromInputStream(
        file.map(f => Files.newInputStream(f)).getOrElse(System.in))
      source.drop(offset * 8L)
    HexDumpFormat.Default
      .withAnsi(!noColor)
      .withAddressOffset(offset.toInt)
      .withLengthLimit(limit.getOrElse(Long.MaxValue))
      .print(data)

