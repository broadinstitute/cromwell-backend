package cromwell.backend.model

import cromwell.caching.ExecutionHash
import wdl4s.values.WdlValue

import scala.concurrent.Future


sealed trait TaskStatus extends ExecutionEvent

sealed trait NonTerminalTaskStatus extends TaskStatus
final case class CreatedTaskStatus(stdout: String, stderr: String) extends NonTerminalTaskStatus
case object RunningTaskStatus extends NonTerminalTaskStatus

sealed trait TerminalTaskStatus extends TaskStatus
final case class SucceededTaskStatus(outputs: Map[String, WdlValue], returnCode: Int, hash: ExecutionHash) extends TerminalTaskStatus
case object StoppedTaskStatus extends TerminalTaskStatus

sealed trait FailedTaskStatus extends TerminalTaskStatus
final case class FailedWithoutReturnCodeTaskStatus(error: Throwable) extends FailedTaskStatus
final case class FailedWithReturnCodeTaskStatus(error: Throwable, returnCode: Int) extends FailedTaskStatus

object Implicits {
  implicit class EnhancedTerminalTaskStatus(val status: TerminalTaskStatus) extends AnyVal {
    def future: Future[TerminalTaskStatus] = Future.successful(status)
  }
}
