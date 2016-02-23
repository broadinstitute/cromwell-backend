package cromwell.backend.provider.local

import java.nio.file.{Files, Path, Paths}
import java.util.regex.Pattern

import akka.actor.{ActorRef, Props}
import akka.util.Timeout
import better.files._
import com.typesafe.scalalogging.StrictLogging
import cromwell.backend.BackendActor
import cromwell.backend.model._
import cromwell.backend.provider.local.FileExtensions._
import cromwell.caching.computeWdlValueHash
import org.apache.commons.codec.digest.DigestUtils
import wdl4s.WdlExpression
import wdl4s.types.{WdlArrayType, WdlFileType, WdlType}
import wdl4s.values.{WdlArray, WdlSingleFile, WdlValue}

import scala.annotation.tailrec
import scala.collection.immutable.ListMap
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.{Await, Future}
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.sys.process._
import scala.util.Try

object LocalBackendActor {
  // Folders
  val CromwellExecutionDir = "cromwell-executions"

  // Files
  val ReturnCodeFile = "rc"
  val StdoutFile = "stdout"
  val StderrFile = "stderr"
  val ScriptFile = "script"

  // Other
  val DockerFlag = "docker"
  val ContinueOnRcFlag = "continueOnReturnCode"
  val FailOnStderrFlag = "failOnStderr"

  case class OutputStmtEval(lhs: WdlType, rhs: Try[WdlValue])

  def props(task: TaskDescriptor): Props = Props(new LocalBackendActor(task))
}

/**
  * Executes a task in local computer through command line. It can be also executed in a Docker container.
  *
  * @param task Task descriptor.
  */
class LocalBackendActor(task: TaskDescriptor) extends BackendActor with StrictLogging {

  import LocalBackendActor._

  implicit val timeout = Timeout(5 seconds)
  private val subscriptions = ArrayBuffer[Subscription[ActorRef]]()
  private var processAbortFunc: Option[() => Unit] = None

  val workingDir = task.workingDir
  val taskWorkingDir = task.name
  val shardId = task.index
  val executionDir = shardId match {
    case Some(index) => Paths.get(CromwellExecutionDir, workingDir, taskWorkingDir, index.toString).toAbsolutePath
    case None =>  Paths.get(CromwellExecutionDir, workingDir, taskWorkingDir).toAbsolutePath
  }
  val stdout = Paths.get(executionDir.toString, StdoutFile)
  val stderr = Paths.get(executionDir.toString, StderrFile)
  val script = Paths.get(executionDir.toString, ScriptFile)
  val returnCode = Paths.get(executionDir.toString, ReturnCodeFile)
  val stdoutWriter = stdout.untailed
  val stderrTailed = stderr.tailed(100)
  val argv = Seq("/bin/bash", script.toString)
  val dockerImage = getRuntimeAttribute(task.runtimeAttributes, DockerFlag)
  val continueOnRc = getRuntimeAttribute(task.runtimeAttributes, ContinueOnRcFlag)
  val failOnStderr = getRuntimeAttribute(task.runtimeAttributes, FailOnStderrFlag)
  val expressionEval = new WorkflowEngineFunctions(executionDir)

  /**
    * Prepare the task and context for execution.
    */
  override def prepare(): Unit = {
    logger.debug(s"Creating execution folder: $executionDir")
    executionDir.toString.toFile.createIfNotExists(true)

    try {
      val command = initiateCommand()
      logger.debug(s"Creating bash script for executing command: $command.")
      writeBashScript(command, executionDir)
      notifyToSubscribers(new TaskStatus(Status.Created))
    } catch {
      case ex: Exception => notifyToSubscribers(new TaskFinalStatus(Status.Failed, FailureResult(ex)))
    }
  }

  /**
    * Stops a task execution.
    */
  override def stop(): Unit = {
    processAbortFunc.get.apply()
    notifyToSubscribers(new TaskStatus(Status.Canceled))
  }

  /**
    * Executes task in given context.
    */
  override def execute(): Unit = {
    notifyToSubscribers(executeTask())
  }

  /**
    * Performs a cleanUp after the task was executed.
    */
  override def cleanUp(): Unit = ()

  /**
    * Subscribe to events on backend.
    */
  override def subscribeToEvent[A](subscription: Subscription[A]): Unit = {
    val sub = subscription.asInstanceOf[Subscription[ActorRef]]
    subscriptions += sub
    sub.subscriber ! Subscribed
  }

  /**
    * Unsubscribe to events on backend.
    */
  override def unsubscribeToEvent[A](subscription: Subscription[A]): Unit = {
    val sub = subscription.asInstanceOf[Subscription[ActorRef]]
    subscriptions -= sub
    sub.subscriber ! Unsubscribed
  }

  /**
    * Returns hash based on TaskDescriptor attributes.
    *
    * @return Return hash for related task.
    */
  override def computeHash: Future[ExecutionHash] = {
    val orderedInputs = task.inputs.toSeq.sortBy(_._1)
    val orderedOutputs = task.outputs.sortWith((l, r) => l.name > r.name)
    val orderedRuntime = ListMap(task.runtimeAttributes.toSeq.sortBy(_._1):_*)
    val overallHash = Seq(
      task.commandTemplate,
      orderedInputs map { case (k, v) => s"$k=${computeWdlValueHash(v)}" } mkString "\n",
      // TODO: Docker hash computation is missing. In case it exists.
      orderedRuntime map { case (k, v) => s"$k=$v" } mkString "\n",
      orderedOutputs map { o => s"${o.wdlType.toWdlString} ${o.name} = ${o.requiredExpression.toWdlString}" } mkString "\n"
    ).mkString("\n---\n")

    Future.successful(ExecutionHash(DigestUtils.md5Hex(overallHash)))
  }

  /**
    * Notifies to subscribers about a new event while executing the task.
    *
    * @param message A task status event.
    */
  private def notifyToSubscribers(message: ExecutionEvent): Unit = {
    subscriptions.filter(subs => subs.eventType.isInstanceOf[ExecutionEvent]).foreach(
      subs => subs.subscriber ! message)
  }

  /**
    * Gather Docker image name from runtime attributes. If it's not present returns none.
    *
    * @param runtimeAttributes Runtime requirements for the task.
    */
  private def getRuntimeAttribute(runtimeAttributes: Map[String, String], key: String): Option[String] = {
    val dockerFlag = runtimeAttributes.filter(p => p._1.equals(key))
    if (dockerFlag.size == 1 && !dockerFlag.values.head.isEmpty) Option(dockerFlag.values.head) else None
  }

  /**
    * Extracts folder path from specific input file.
    *
    * @param file Absolute path from input file.
    * @return File's folder.
    */
  private def extractFolder(file: String): Option[String] = {
    try {
      Option(file.substring(0, file.lastIndexOf("/")))
    } catch {
      case oooe: StringIndexOutOfBoundsException =>
        logger.warn("Input with no valid folder pattern. It may be a intermediate value.", oooe)
        None
    }
  }

  /**
    * Creates docker command in order to execute the task into a container.
    *
    * @param image Docker image name.
    * @return Command to execute.
    */
  private def buildDockerRunCommand(image: String): String = {
    val dockerVolume = "-v %s:%s"
    val inputFolderList = extractFolderFromInput(task.inputs.values.toList)
    val inputVolumes = inputFolderList match {
      case a: List[String] => inputFolderList.map(v => dockerVolume.format(v, v)).mkString(" ")
      case _ => ""
    }
    val outputVolume = dockerVolume.format(executionDir, executionDir)
    log.debug(s"DockerInputVolume: $inputVolumes")
    log.debug(s"DockerOutputVolume: $outputVolume")
    val dockerCmd = s"docker run %s %s --rm %s %s" //TODO: make it configurable from file.
    dockerCmd.format(inputVolumes, outputVolume, image, argv.mkString(" ")).replace("  ", " ")
  }

  /**
    * Extract folder path from input files.
    *
    * @return A list with folders to be mount in a docker container.
    */
  private def extractFolderFromInput(wdlValueList: List[WdlValue]): List[String] = {
    wdlValueList match {
      case head :: tail =>
        head.wdlType match {
          case WdlFileType => List(extractFolder(head.toWdlString.replace("\"", "")).get) ::: extractFolderFromInput(wdlValueList.tail)
          case WdlArrayType(WdlFileType) => extractFolderFromInput(head.asInstanceOf[WdlArray].value.toList) ::: extractFolderFromInput(wdlValueList.tail)
          case _ => extractFolderFromInput(wdlValueList.tail)
        }
      case Nil => List()
    }
  }

  /**
    * Writes the script file containing the user's command from the WDL as well
    * as some extra shell code for monitoring jobs
    */
  private def writeBashScript(taskCommand: String, executionPath: Path): Unit = {
    script.write(
      s"""#!/bin/sh
          |cd $executionPath
          |$taskCommand
          |echo $$? > rc
          |""".stripMargin)
  }

  /**
    * Resolves absolute path for output files, resolving a non-absolute path to the context of the executionDirectory
    * if necessary.
    *
    * @param output Pair of WdlType and WdlValue
    * @return WdlValue with absolute path if it is a file.
    */
  private def resolveOutputValue(output: OutputStmtEval): WdlValue = {
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

  /**
    * Looks for return code resulted from task in the 'ContinueOnReturnCode' entry from runtime requirements.
    *
    * @param returnCode Return code obtained from task execution.
    * @return True if this RC is contained otherwise false.
    */
  private def isInContinueOnReturnCode(returnCode: Int): Boolean = {
    continueOnRc match {
      case Some(codes) => getContinueOnReturnCodeSet(codes).contains(returnCode) || getContinueOnReturnCodeFlag(codes)
      case None => false
    }
  }

  /**
    * Checks if the string in 'ContinueOnReturnCode' entry from runtime requirements contains a Boolean value.
    *
    * @param continueOnRcValue String from runtime requirements definition in the WDL file.
    * @return If it is defined, it returns the Boolean value otherwise false.
    */
  private def getContinueOnReturnCodeFlag(continueOnRcValue: String): Boolean = {
    try {
      continueOnRcValue.toBoolean
    } catch {
      case ex: Exception => false
    }
  }

  /**
    * Tries to get a list of return codes in 'ContinueOnReturnCode' entry from runtime requirements.
    *
    * @param continueOnRcValue String from runtime requirements definition in the WDL file.
    * @return If there are values, it returns those values otherwise an empty list.
    */
  private def getContinueOnReturnCodeSet(continueOnRcValue: String): List[Int] = {
    try {
      continueOnRcValue.split(" ").toList.map(_.toInt)
    } catch {
      case nfe: NumberFormatException => List()
    }
  }

  /**
    * Run a command using a bash script.
    *
    * @return A TaskStatus with the final status of the task.
    */
  private def executeTask(): TaskFinalStatus = {
    val commandToExecute: Seq[String] = dockerImage match {
      case Some(image) => buildDockerRunCommand(image).split(" ").toSeq
      case None => argv
    }

    val process = commandToExecute.run(ProcessLogger(stdoutWriter writeWithNewline, stderrTailed writeWithNewline))
    processAbortFunc = Option(() => process.destroy())
    notifyToSubscribers(new TaskStatus(Status.Running))
    val backendCommandString = argv.map(s => "\"" + s + "\"").mkString(" ")
    logger.debug(s"command: $backendCommandString")
    val processReturnCode = process.exitValue() // blocks until process finishes

    List(stdoutWriter.writer, stderrTailed.writer).foreach(_.flushAndClose())

    val stderrFileLength = Try(Files.size(stderr)).getOrElse(0L)
    val failOnErrFlag = failOnStderr.getOrElse("false").toBoolean
    lazy val rc = Try(returnCode.contentAsString.stripLineEnd.toInt)

    if (processReturnCode != 0) {
      TaskFinalStatus(Status.Failed, FailureTaskResult(
        new IllegalStateException("Execution process failed."), processReturnCode, stderr.toString))
    } else if (rc.isFailure) {
      // case where docker fails.
      TaskFinalStatus(Status.Failed, FailureTaskResult(rc.failed.get, processReturnCode, stderr.toString))
    } else if (failOnErrFlag && stderrFileLength > 0) {
      // rc status is validated in previous step so it is safe to use .get
      TaskFinalStatus(Status.Failed, FailureTaskResult(
        new IllegalStateException("StdErr file is not empty."), rc.get, stderr.toString))
    } else if (rc.get != 0 && !isInContinueOnReturnCode(rc.get)) {
      TaskFinalStatus(Status.Failed, FailureTaskResult(
        new IllegalStateException(s"Return code is nonzero. Value: ${rc.get}"), rc.get, stderr.toString))
    } else {
      def lookupFunction: String => WdlValue = WdlExpression.standardLookupFunction(task.inputs, task.declarations, expressionEval)
      val outputExpressions = task.outputs.map(
        output => output.name -> OutputStmtEval(output.wdlType, output.requiredExpression.evaluate(lookupFunction, expressionEval)))
      processOutputResult(rc.get, outputExpressions.toMap)
    }
  }

  /**
    * Process output evaluating expressions, checking for created files and converting WdlString to WdlSimpleFile if necessary.
    *
    * @param processReturnCode Return code from process.
    * @param outputExpressions Outputs.
    * @return TaskStatus with final status of the task.
    */
  private def processOutputResult(processReturnCode: Int, outputExpressions: Map[String, OutputStmtEval]): TaskFinalStatus = {
    if (outputExpressions.values.exists(_.rhs.isFailure)) {
      TaskFinalStatus(Status.Failed, FailureTaskResult(new IllegalStateException("Failed to evaluate output expressions.",
        outputExpressions.values.collectFirst { case v if v.rhs.isFailure => v.rhs.failed.get } get), processReturnCode, stderr.toString))
    } else {
      try {
        // FIXME this code is not properly structured to deal with asynchronous hash computation.  The Await.result is
        // gross but safe since the code isn't actually pulling down Docker image hashes, so the `Future` we're getting
        // back is currently just a `Future.successful`.
        TaskFinalStatus(
          Status.Succeeded, SuccessfulTaskResult(outputExpressions.mapValues(resolveOutputValue), Await.result(computeHash, Duration.Inf)))
      } catch {
        case ex: Exception => TaskFinalStatus(Status.Failed, FailureTaskResult(ex, processReturnCode, stderr.toString))
      }
    }
  }

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
  private def normalize(s: String): String = {
    val trimmed = stripAll(s, "\r\n", "\r\n \t")
    val parts = trimmed.split("[\\r\\n]+")
    val indent = parts.map(leadingWhitespaceCount).min
    parts.map(_.substring(indent)).mkString("\n")
  }

  private def leadingWhitespaceCount(s: String): Int = {
    val Ws = Pattern.compile("[\\ \\t]+")
    val matcher = Ws.matcher(s)
    if (matcher.lookingAt) matcher.end else 0
  }

  private def stripAll(s: String, startChars: String, endChars: String): String = {
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

  private def initiateCommand(): String = {
    normalize(task.commandTemplate.map(_.instantiate(task.declarations, task.inputs, expressionEval)).mkString(""))
  }

  override def name = "Local"
}
