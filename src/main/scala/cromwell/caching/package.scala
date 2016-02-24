package cromwell

import better.files.Cmds._
import better.files._
import org.apache.commons.codec.digest.DigestUtils
import wdl4s.values._

import scala.collection.immutable.ListMap

package object caching {

  /**
    * Hash of task execution.
    * @param overallHash Contains a hash from TaskDescriptor.
    * @param dockerHash Contains docker image hash.
    */
  case class ExecutionHash(overallHash: String, dockerHash: Option[String] = None)

  def computeWdlValueHash(wdlValue: WdlValue): String = {
    wdlValue match {
      case w: WdlObject => w.value mapValues computeWdlValueHash mkString ""
      case w: WdlMap => DigestUtils.md5Hex(ListMap(w.value.toSeq.sortBy(_._1.valueString):_*) map {
        case (k, v) => computeWdlValueHash(k) -> computeWdlValueHash(v) } mkString "")
      case w: WdlArray => DigestUtils.md5Hex(
        w.value.sortBy(_.valueString) map computeWdlValueHash mkString "")
      case w: WdlFile => md5(w.toWdlString.replace("\"", "").toFile).toLowerCase
      case w => DigestUtils.md5Hex(w.valueString)
    }
  }
}
