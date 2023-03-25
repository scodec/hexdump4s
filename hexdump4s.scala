//> using scala "3.3.0-RC3"
//> using lib "org.scodec::scodec-bits::1.1.37"
//> using lib "com.monovore::decline::2.3.1"
//> using lib "co.fs2::fs2-io::3.6.1"
//
// Run with: scala-cli hexdump4s.scala -- <args>
// Build GraalVM native image version with: scala-cli package --native-image hexdump4s.scala -f -- --no-fallback
// Build node.js version with: scala-cli package --js --js-module-kind commonjs hexdump4s.scala -f
// Build Scala Native version with: scala-cli package --native hexdump4s.scala -o hexdump4s -f
//
import scodec.bits._
import com.monovore.decline._
import fs2.{Stream, Pull}
import fs2.io.file.{Files, Path}
import cats.effect.{ExitCode, IO, IOApp}
import cats.syntax.all._

object hexdump4s extends IOApp:
  val MaxSafeLong = 9007199254740991L // For Node.js

  def run(args: List[String]) =
    val command = Command(
      name = "hexdump4s",
      header = "Prints a hex dump of a file"
    ):
      val offset = Opts.option[Long]("offset", short = "s", metavar = "count",
        help = "Number of bytes to skip at start of input").withDefault(0L)
      val length = Opts.option[Long]("length", short = "n", metavar = "count",
        help = "Number of bytes to dump").withDefault(Long.MaxValue / 8)
      val noColor = Opts.flag("no-color", help = "Disables color output").orFalse
      val file = Opts.argument[String](metavar = "file").orNone
      (offset, length, noColor, file).tupled

    command.parse(args) match
      case Left(help) =>
        IO(System.err.println(help)).as(ExitCode(-1))
      case Right((offset, limit, noColor, file)) =>
        val data: Stream[IO, Byte] = file match
          case None =>
            fs2.io.stdin[IO](16 * 16).drop(offset)
          case Some(f) =>
            Files[IO].readRange(Path(f), 64 * 1024, offset, MaxSafeLong)

        def paginate(pageSize: Int)(s: Stream[IO, Byte]): Stream[IO, ByteVector] =
          def go(s: Stream[IO, Byte], carry: ByteVector): Pull[IO, ByteVector, Unit] =
            s.pull.uncons.flatMap:
              case Some((hd, tl)) =>
                val acc = carry ++ hd.toByteVector
                val mod = acc.size % pageSize
                Pull.output1(acc.dropRight(mod)) >> go(tl, acc.takeRight(mod))
              case None => Pull.output1(carry)
          go(s, ByteVector.empty).stream

        def trackAddress(bs: Stream[IO, ByteVector]): Stream[IO, (Int, ByteVector)] =
          bs.mapAccumulate(offset)((address, b) => (address + b.size, b))
            .map((address, b) => ((address - b.size).toInt, b))

        val fmt = HexDumpFormat.Default.withAnsi(!noColor)

        data
          .take(limit)
          .through(paginate(16))
          .through(trackAddress)
          .foreach: (address, b) =>
            IO(fmt.withAddressOffset(address.toInt).print(b))
          .compile.drain.as(ExitCode.Success)
