package cromwell.caching

import java.nio.file.{Paths, Path}
import better.files._, Cmds._
import org.apache.commons.codec.digest.DigestUtils
import wdl4s.values._

import scala.collection.immutable.ListMap

package object caching {
  type Md5sum = String

  def getAbsolutePath(executionPath: Path, file: String): Path = {
    val absolutePath = Paths.get(executionPath.toString, file)
    if (absolutePath.exists)
      absolutePath
    else
      throw new IllegalStateException(s"$file does not exist.")
  }

  def computeWdlValueHash(wdlValue: WdlValue, executionPath: Path): Md5sum = {
    wdlValue match {
      case w: WdlObject => w.value mapValues { computeWdlValueHash(_, executionPath) } mkString ""
      case w: WdlMap => DigestUtils.md5Hex(ListMap(w.value.toSeq.sortBy(_._1.valueString):_*) map {
        case (k, v) => computeWdlValueHash(k, executionPath) -> computeWdlValueHash(v, executionPath) } mkString "")
      case w: WdlArray => DigestUtils.md5Hex(
        w.value.sortBy(_.valueString) map { computeWdlValueHash(_, executionPath) } mkString "")
      case w: WdlFile => md5(getAbsolutePath(executionPath, w.value)).toLowerCase
      case w => DigestUtils.md5Hex(w.valueString)
    }
  }
}
