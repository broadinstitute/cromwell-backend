package cromwell.backend.provider.htcondor

import java.nio.file.Path

import cromwell.backend.provider.local.FileExtensions._


case class CondorJobId(clusterId: Int, jobId: Int)

case class CondorJob(cmd: String, condorJobId: CondorJobId)

trait HtCondorWrapper {

  def createSubmitCommand(path: Path, attributes: Map[String, String]): String = {
    def htCondorSubmitCommand(filePath: Path) = {
      s"${HtCondorWrapper.condor_submit} ${filePath.toString}"
    }

    val submitFileWriter = path.untailed
    attributes.foreach(attribute => submitFileWriter.writeWithNewline(s"${attribute._1}=${attribute._2}"))
    submitFileWriter.writeWithNewline(HtCondorRuntimeKeys.queue)

    htCondorSubmitCommand(path.getFileName)
  }

  def jobStatusProcess(condorJobId: CondorJobId)

  def jobRemoveProcess(condorJobId: CondorJobId)
}

object HtCondorWrapper {
  val submit_output_pattern = "(\\d*) job\\(s\\) submitted to cluster (\\d*)\\."
  val condor_submit = "condor_submit"
  val condor_queue = "condor_q"
  val condor_remove = "condor_rm"
}

object HtCondorRuntimeKeys {
  val executable ="executable"
  val arguments = "arguments"
  val error = "error"
  val output = "output"
  val log = "log"
  val queue ="queue"
  val rank ="rank"
  val requirements ="requirements"
  val requestMemory = "request_memory"
  val cpu ="request_cpus"
  val disk = "request_disk"
}




