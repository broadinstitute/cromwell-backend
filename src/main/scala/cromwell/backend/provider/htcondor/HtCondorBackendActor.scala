package cromwell.backend.provider.htcondor

import java.nio.file.{Files, Paths}
import java.util.regex.Pattern

import akka.actor.{ActorRef, Props}
import akka.util.Timeout
import better.files._
import com.typesafe.scalalogging.StrictLogging
import cromwell.backend.BackendActor
import cromwell.backend.model._
import cromwell.backend.provider._
import cromwell.backend.provider.local.FileExtensions._
import cromwell.backend.provider.local.WorkflowEngineFunctions
import cromwell.caching.caching.Md5sum
import scala.annotation.tailrec
import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.sys.process.ProcessLogger
import scala.util.Try

object HtCondorBackendActor {
  // Files
  val returnCode = "rc"
  val stdout = "stdout"
  val stderr = "stderr"
  val script = "script"
  val submit = "submit"

  val htCondorCmds = new HtCondorCommands {}
  val htCondorProcess = new HtCondorProcess {}
  val htCondorParser = new HtCondorClassAdParser {}

  def props(task: TaskDescriptor, cmd: HtCondorCommands, parser: HtCondorClassAdParser, process: HtCondorProcess): Props =
    Props(new HtCondorBackendActor(task,cmd,parser,process))

  def props(task: TaskDescriptor): Props = Props(new HtCondorBackendActor(task, htCondorCmds, htCondorParser, htCondorProcess))
}

class HtCondorBackendActor(task: TaskDescriptor, cmds: HtCondorCommands, parser: HtCondorClassAdParser, extProcess: HtCondorProcess)
  extends BackendActor with StrictLogging{

  import HtCondorBackendActor._

  implicit val timeout = Timeout(5 seconds)
  private val subscriptions = ArrayBuffer[Subscription[ActorRef]]()
  private var submitProcessAbortFunc: Option[() => Unit] = None

  val workingDir = task.workingDir
  val taskWorkingDir = task.name
  val shardId = task.index
  val executionDir = shardId match {
    case Some(index) => Paths.get(workingDir, taskWorkingDir, index.toString).toAbsolutePath
    case None => Paths.get(workingDir, taskWorkingDir).toAbsolutePath
  }

  val stdoutPath = Paths.get(executionDir.toString, stdout)
  val stderrPath = Paths.get(executionDir.toString, stderr)
  val scriptPath = Paths.get(executionDir.toString, script)
  val submitPath = Paths.get(executionDir.toString, s"$taskWorkingDir.$submit")
  val returnCodePath = Paths.get(executionDir.toString, returnCode)
  lazy val stdoutWriter = extProcess.getUntailedWriter(stdoutPath)
  lazy val stderrWriter = extProcess.getTailedWriter(100, stderrPath)
  val argv = extProcess.getCommandList(scriptPath.toString)
  val expressionEval = new WorkflowEngineFunctions(executionDir)

  /**
    * Prepare the task and context for execution.
    */
  override def prepare(): Unit = {
    logger.debug(s"Creating execution folder: $executionDir")
    executionDir.toString.toFile.createIfNotExists(true)
    try {
      val command = task.initiateCommand(expressionEval)
      logger.debug(s"Creating bash script for executing command: $command.")
      //TODO: need to access other requirements for submit file from runtime requirements
      val attributes = Map(HtCondorRuntimeKeys.executable -> command,
        HtCondorRuntimeKeys.output -> s"$stdoutPath",
        HtCondorRuntimeKeys.error -> s"$stderrPath")
      val condorSubmitFile = cmds.submitCommand(submitPath, attributes)
      scriptPath.writeBashScript(condorSubmitFile, executionDir)
      notifyToSubscribers(new TaskStatus(Status.Created))
    } catch {
      case ex: Exception => notifyToSubscribers(new TaskFinalStatus(Status.Failed, FailureResult(ex)))
    }
  }

  /**
    * Stops a task execution.
    */
  override def stop(): Unit = {
    submitProcessAbortFunc.get.apply()
    notifyToSubscribers(new TaskStatus(Status.Canceled))
  }

  /**
    * Executes task in given context.
    */
  override def execute(): Unit = notifyToSubscribers(executeTask())

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
  override def computeHash: Md5sum = task.computeHash


  /**
    * Notifies to subscribers about a new event while executing the task.
    *
    * @param message A task status event.
    */
  override def notifyToSubscribers[A](message: A): Unit = {
    subscriptions.filter(subs => subs.eventType.isInstanceOf[ExecutionEvent]).foreach(
      subs => subs.subscriber ! message)
  }

  private def executeTask(): TaskFinalStatus = {
    val process = extProcess.externalProcess(argv, ProcessLogger(stdoutWriter writeWithNewline, stderrWriter writeWithNewline))
    submitProcessAbortFunc = Option(() => process.destroy())
    notifyToSubscribers(new TaskStatus(Status.Running))
    val processReturnCode = process.exitValue() // blocks until process finishes
    logger.debug(s"processReturnCode  : $processReturnCode")

    List(stdoutWriter.writer, stderrWriter.writer).foreach(_.flushAndClose())
    logger.debug(s"done flushing")
    val stderrFileLength = Try(Files.size(stderrPath)).getOrElse(0L)
    logger.debug(s"stderr file length : $stderrFileLength ")

    lazy val rc = Try(returnCodePath.contentAsString.stripLineEnd.toInt)
    logger.debug(s"received return code ")

    if (processReturnCode != 0) {
      notifyToSubscribers(new TaskStatus(Status.Failed))
      TaskFinalStatus(Status.Failed, FailureTaskResult(
        new IllegalStateException("Execution process failed."), processReturnCode, stderrPath.toString))
    } else if (rc.isFailure) {
      notifyToSubscribers(new TaskStatus(Status.Failed))
      TaskFinalStatus(Status.Failed, FailureTaskResult(rc.failed.get, processReturnCode, stderrPath.toString))
    } else if (stderrFileLength > 0) {
      // rc status is validated in previous step so it is safe to use .get
      notifyToSubscribers(new TaskStatus(Status.Failed))
      TaskFinalStatus(Status.Failed, FailureTaskResult(
        new IllegalStateException("StdErr file is not empty."), rc.get, stderrPath.toString))
    } else {
      logger.debug(s"parse stdout output file")
      val pattern = Pattern.compile(HtCondorCommands.submit_output_pattern)
      //Number of lines in stdout for submit job will be 3 at max therefore reading all lines at once.
      logger.debug(s"output of submit process : ${stdoutPath.lines.toList}")
      val line = stdoutPath.lines.toList.last
      val matcher = pattern.matcher(line)
      logger.debug(s"submit process stdout last line : $line")
      if (!matcher.matches()) TaskFinalStatus(Status.Failed, FailureTaskResult(
        new IllegalStateException("failed to retrive jobs(id) and cluster id"), processReturnCode, stderrPath.toString))
      val jobId = matcher.group(1).toInt
      val clusterId = matcher.group(2).toInt
      logger.debug(s"task job is : $jobId and cluster id is : $clusterId")
      trackTaskStatus(CondorJob(CondorJobId(jobId, clusterId)))
    }
  }

  private def trackTaskStatus(job: CondorJob): TaskFinalStatus = {
    val process = extProcess.externalProcess(extProcess.getCommandList(cmds.statusCommand()))
    val processReturnCode = process.exitValue() // blocks until process finishes
    logger.debug(s"trackTaskStatus -> processReturnCode : $processReturnCode and stderr : ${extProcess.getProcessStderr().size}")

    if (processReturnCode != 0 || extProcess.getProcessStderr().nonEmpty)
      return TaskFinalStatus(Status.Failed, FailureTaskResult(
        new IllegalStateException("StdErr file is not empty."), processReturnCode, stderrPath.toString))

    val status = parser.getJobStatus(extProcess.getProcessStdout(), job.condorJobId)
    notifyToSubscribers(status)
    if (status.status == Status.Succeeded) //TODO: probably needs to add some delay here
      return task.evaluateTaskOutputExpression(processReturnCode, expressionEval, stderrPath, executionDir)
    if (status.status == Status.Running || status.status == Status.Created)
      return trackTaskStatus(job)

    return TaskFinalStatus(Status.Failed, FailureTaskResult(
      new IllegalStateException("StdErr file is not empty."), processReturnCode, stderrPath.toString))
  }
}