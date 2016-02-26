package cromwell.backend

import java.nio.file.{Path, Paths}
import java.util.regex.Pattern

import cromwell.backend.model._
import better.files._
import cromwell.backend.provider.local.WorkflowEngineFunctions
import cromwell.caching.caching
import org.apache.commons.codec.digest.DigestUtils
import wdl4s.WdlExpression
import wdl4s.types.{WdlFileType, WdlArrayType, WdlType}
import wdl4s.values.{WdlArray, WdlSingleFile, WdlValue}

import scala.annotation.tailrec
import scala.collection.immutable.ListMap
import scala.util.Try



package object provider {

  implicit class StringOperationsExtension(task: TaskDescriptor) {

    /**
      * 1) Remove all leading newline chars
      * 2) Remove all trailing newline AND whitespace chars
      * 3) Remove all *leading* whitespace that's common among every line in the input string
      * For example, the input string:
      * "
      * first line
      * second line
      * third line
      *
      * "
      * Would be normalized to:
      * "first line
      * second line
      * third line"
      *
      * @param s String to process
      * @return String which has common leading whitespace removed from each line
      */
    def normalize(s: String): String = {
      val trimmed = stripAll(s, "\r\n", "\r\n \t")
      val parts = trimmed.split("[\\r\\n]+")
      val indent = parts.map(leadingWhitespaceCount).min
      parts.map(_.substring(indent)).mkString("\n")
    }

    def leadingWhitespaceCount(s: String): Int = {
      val Ws = Pattern.compile("[\\ \\t]+")
      val matcher = Ws.matcher(s)
      if (matcher.lookingAt) matcher.end else 0
    }

    def stripAll(s: String, startChars: String, endChars: String): String = {
      /* https://stackoverflow.com/questions/17995260/trimming-strings-in-scala */
      @tailrec
      def start(n: Int): String = {
        if (n == s.length) ""
        else if (startChars.indexOf(s.charAt(n)) < 0) end(n, s.length)
        else start(1 + n)
      }

      @tailrec
      def end(a: Int, n: Int): String = {
        if (n <= a) s.substring(a, n)
        else if (endChars.indexOf(s.charAt(n - 1)) < 0) s.substring(a, n)
        else end(a, n - 1)
      }

      start(0)
    }

    def initiateCommand(expressionEval: WorkflowEngineFunctions): String = {
      normalize(task.commandTemplate.map(_.instantiate(task.declarations, task.inputs, expressionEval)).mkString(""))
    }
  }

  implicit class TaskOutputExpressionEvaluation(task: TaskDescriptor) {

    case class OutputStmtEval(lhs: WdlType, rhs: Try[WdlValue])

    def evaluateTaskOutputExpression(rc: Int, expressionEval: WorkflowEngineFunctions, stderr: Path, executionDir: Path): TaskFinalStatus = {
      def lookupFunction: String => WdlValue = WdlExpression.standardLookupFunction(task.inputs, task.declarations, expressionEval)
      val outputsExpressions = task.outputs.map(
        output => output.name -> OutputStmtEval(output.wdlType, output.requiredExpression.evaluate(lookupFunction, expressionEval)))
      processOutputResult(rc, outputsExpressions, stderr, executionDir)
    }

    /**
      * Process output evaluating expressions, checking for created files and converting WdlString to WdlSimpleFile if necessary.
      *
      * @param processReturnCode  Return code from process.
      * @param outputsExpressions Outputs.
      * @return TaskStatus with final status of the task.
      */
    def processOutputResult(processReturnCode: Int, outputsExpressions: Seq[(String, OutputStmtEval)], stderr: Path, executionDir: Path): TaskFinalStatus = {
      if (outputsExpressions.filter(_._2.rhs.isFailure).size > 0) {
        TaskFinalStatus(Status.Failed, FailureTaskResult(new IllegalStateException("Failed to evaluate output expressions.",
          outputsExpressions.filter(_._2.rhs.isFailure).head._2.rhs.failed.get), processReturnCode, stderr.toString))
      } else {
        try {
          TaskFinalStatus(Status.Succeeded, SuccessfulTaskResult(outputsExpressions.map(
            output => output._1 -> resolveOutputValue(output._2, executionDir)).toMap, new ExecutionHash(task.computeHash, None)))
        } catch {
          case ex: Exception => TaskFinalStatus(Status.Failed, FailureTaskResult(ex, processReturnCode, stderr.toString))
        }
      }
    }

    /**
      * Resolves absolute path for output files, resolving a non-absolute path to the context of the executionDirectory
      * if necessary.
      *
      * @param output Pair of WdlType and WdlValue
      * @return WdlValue with absolute path if it is a file.
      */
    def resolveOutputValue(output: OutputStmtEval, executionDir: Path): WdlValue = {
      def getAbsolutePath(file: String): Path = {
        val asIs = Paths.get(file)
        val path = if (asIs.isAbsolute) asIs else Paths.get(executionDir.toString, file)

        if (path.exists) path else throw new IllegalStateException(s"Output file ${path.toFile.getAbsolutePath} does not exist.")
      }

      //TODO: check for map of files. Is going to be supported that use case?
      val lhsType = output.lhs
      val rhsType = output.rhs.get.wdlType
      rhsType match {
        case rhs if rhs == WdlFileType | lhsType == WdlFileType =>
          new WdlSingleFile(getAbsolutePath(output.rhs.get.toWdlString.replace("\"", "").trim).toString)
        case rhs if rhs == WdlArrayType(WdlFileType) | lhsType == WdlArrayType(WdlFileType) =>
          output.rhs.get.asInstanceOf[WdlArray].map(wdlValue =>
            new WdlSingleFile(getAbsolutePath(wdlValue.toWdlString.replace("\"", "").trim).toString))
        case rhs if rhs == lhsType => output.rhs.get
        case rhs => lhsType.coerceRawValue(output.rhs.get).get
      }
    }

  }

  implicit class CacheableHash(task: TaskDescriptor) {
    /**
      * Returns hash based on TaskDescriptor attributes.
      *
      * @return Return hash for related task.
      */
    def computeHash: String = {
      val orderedInputs = task.inputs.toSeq.sortBy(_._1)
      val orderedOutputs = task.outputs.sortWith((l, r) => l.name > r.name)
      val orderedRuntime = ListMap(task.runtimeAttributes.toSeq.sortBy(_._1): _*)
      val overallHash = Seq(
        task.commandTemplate,
        orderedInputs map { case (k, v) => s"$k=${caching.computeWdlValueHash(v)}" } mkString "\n",
        // TODO: Docker hash computation is missing. In case it exists.
        orderedRuntime map { case (k, v) => s"$k=$v" } mkString "\n",
        orderedOutputs map { o => s"${o.wdlType.toWdlString} ${o.name} = ${o.requiredExpression.toWdlString}" } mkString "\n"
      ).mkString("\n---\n")

      DigestUtils.md5Hex(overallHash)
    }
  }

  implicit class BashScriptExtension(path: Path) {

    /**
      * Writes the script file containing the user's command from the WDL as well
      * as some extra shell code for monitoring jobs
      */
    def writeBashScript(taskCommand: String, executionPath: Path): Unit = {
      path.write(
        s"""#!/bin/sh
            |cd $executionPath
            |$taskCommand
            |echo $$? > rc
            |""".stripMargin)
    }

  }

}
