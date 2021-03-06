package ammonite.repl.frontend

import java.io.{OutputStream, InputStream}

import ammonite.repl.{Evaluated, Result}
import jline.console.ConsoleReader
import acyclic.file

import scala.tools.nsc.interpreter._
import collection.JavaConversions._

/**
 * All the mucky JLine interfacing code
 */
class JLineFrontend(input: InputStream,
                    output: OutputStream,
                    shellPrompt: => String,
                    previousImportBlock: => String,
                    compilerComplete: => (Int, String) => (Int, Seq[String]))
                    extends jline.console.completer.Completer {
  val term = new jline.UnixTerminal()
  var buffered = ""
  term.init()
  val reader = new ConsoleReader(input, output, term)

  reader.setHistoryEnabled(true)
  reader.addCompleter(this)
  reader.setExpandEvents(false)

  def complete(_buf: String, cursor: Int, candidates: JList[CharSequence]): Int = {
    val buf   = if (_buf == null) "" else _buf
    val prevImports = previousImportBlock
    val prev = prevImports + "\nobject Foo{\n"
    import collection.JavaConversions._
    val (completionBase, completions) = compilerComplete(
      cursor + prev.length,
      prev + buf + "\n}"
    )
    candidates.addAll(completions)
    completionBase - prev.length
  }

  def history =
    reader.getHistory
          .entries()
          .map(_.value().toString)
          .toVector

  def action(): Result[String] = for {
    _ <- Signaller("INT") {
      if (reader.getCursorBuffer.length() == 0) {
        println("Ctrl-D to exit")
      } else {
        reader.setCursorPosition(0)
        reader.killLine()
      }
    }

    res <- Option(
      reader.readLine(
        if (buffered == "") shellPrompt + " "
        // Strip ANSI color codes, as described http://stackoverflow.com/a/14652763/871202
        else " " * (shellPrompt.replaceAll("\u001B\\[[;\\d]*m", "").length + 1)
      )
    ).map(Result.Success(_))
      .getOrElse(Result.Exit)

//    _ <- Result.Success[String]("")
  } yield buffered + "\n" + res

  def update(r: Result[Evaluated]) = r match{

    case Result.Buffer(line) =>
      /**
       * Hack to work around the fact that if nothing got entered into
       * the prompt, the `ConsoleReader`'s history wouldn't increase
       */
      if(line != buffered + "\n") reader.getHistory.removeLast()
      buffered = line + "\n"
    case Result.Success(ev) =>
      val last = reader.getHistory.size()-1
      reader.getHistory.set(last, buffered + reader.getHistory.get(last))
      buffered = ""
    case Result.Failure(msg) => buffered = ""
    case _ =>
  }
}
