package cromwell.caching

import java.nio.file.{Paths, Path}
import better.files._, Cmds._
import org.apache.commons.codec.digest.DigestUtils
import wdl4s.values._

import scala.collection.immutable.ListMap

package object caching {
  type Md5sum = String

  def computeWdlValueHash(wdlValue: WdlValue): Md5sum = {
    wdlValue match {
      case w: WdlObject => w.value mapValues { computeWdlValueHash(_) } mkString ""
      case w: WdlMap => DigestUtils.md5Hex(ListMap(w.value.toSeq.sortBy(_._1.valueString):_*) map {
        case (k, v) => computeWdlValueHash(k) -> computeWdlValueHash(v) } mkString "")
      case w: WdlArray => DigestUtils.md5Hex(
        w.value.sortBy(_.valueString) map { computeWdlValueHash(_) } mkString "")
      case w: WdlFile => md5(w.toWdlString.replace("\"", "").toFile).toLowerCase
      case w => DigestUtils.md5Hex(w.valueString)
    }
  }
}
