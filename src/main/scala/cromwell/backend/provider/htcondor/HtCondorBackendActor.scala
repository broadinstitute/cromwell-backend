package cromwell.backend.provider.htcondor

import java.nio.file.{Path, Paths}

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
import scala.sys.process._

import scala.collection.mutable.ArrayBuffer
import scala.concurrent.duration._
import scala.language.postfixOps
import scala.sys.process.ProcessLogger

object HtCondorBackendActor {
  // Files
  val returnCode = "rc"
  val stdout = "stdout"
  val stderr = "stderr"
  val script = "script"
  val submit = "submit"

  def props(task: TaskDescriptor): Props = Props(new HtCondorBackendActor(task))
}

class HtCondorBackendActor(task: TaskDescriptor) extends BackendActor with StrictLogging with HtCondorWrapper{

  import HtCondorBackendActor._

  implicit val timeout = Timeout(5 seconds)
  private val subscriptions = ArrayBuffer[Subscription[ActorRef]]()
  private var processAbortFunc: Option[() => Unit] = None

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
  val stdoutWriter = stdoutPath.untailed
  val stderrTailed = stderrPath.tailed(100)
  val argv = Seq("/bin/bash", scriptPath.toString)
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
      val attributes = Map(HtCondorRuntimeKeys.executable -> command,
                           HtCondorRuntimeKeys.output -> s"$taskWorkingDir.$stdout",
                           HtCondorRuntimeKeys.error -> s"$taskWorkingDir.$stderr")
      val condorSubmitFile = createSubmitCommand(submitPath,attributes)
      writeBashScript(condorSubmitFile, executionDir)
        notifyToSubscribers (new TaskStatus(Status.Created))
    } catch {
      case ex: Exception => notifyToSubscribers(new TaskFinalStatus(Status.Failed, FailureResult(ex)))
    }
  }

  /**
    * Stops a task execution.
    */
  override def stop(): Unit = ???

  /**
    * Executes task in given context.
    */
  override def execute(): Unit = ???

  /**
    * Performs a cleanUp after the task was executed.
    */
  override def cleanUp(): Unit = ???

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
  override def computeHash: Md5sum = ???

  /**
    * Notifies to subscribers about a new event while executing the task.
    *
    * @param message A task status event.
    */
  override def notifyToSubscribers[A](message: A): Unit = {
    subscriptions.filter(subs => subs.eventType.isInstanceOf[ExecutionEvent]).foreach(
      subs => subs.subscriber ! message)
  }

  /**
    * Writes the script file containing the user's command from the WDL as well
    * as some extra shell code for monitoring jobs
    */
  private def writeBashScript(taskCommand: String, executionPath: Path): Unit = {
    scriptPath.write(
      s"""#!/bin/sh
          |cd $executionPath
          |$taskCommand
          |echo $$? > rc
          |""".stripMargin)
  }

  private def executeTask(): TaskFinalStatus = {
    val process = argv.run(ProcessLogger(stdoutWriter writeWithNewline, stderrTailed writeWithNewline))
    processAbortFunc = Option(() => process.destroy())
    notifyToSubscribers(new TaskStatus(Status.Running))
    //TODO: add logic for reach submit job id 
    Nil
  }

}
