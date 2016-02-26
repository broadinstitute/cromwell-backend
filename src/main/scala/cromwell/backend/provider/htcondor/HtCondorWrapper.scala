package cromwell.backend.provider.htcondor

import java.nio.file.Path
import javax.xml.parsers.SAXParserFactory

import com.typesafe.scalalogging.StrictLogging
import cromwell.backend.model.{Status, TaskStatus}
import cromwell.backend.provider.local.FileExtensions._
import cromwell.backend.provider.local.{TailedWriter, UntailedWriter}
import scala.sys.process._
import scala.xml.XML._
import better.files._
import scala.language.postfixOps

case class CondorJobId(jobId: Int, clusterId: Int)

case class CondorJob(condorJobId: CondorJobId)

case class CondorStatus(condorJobId: CondorJobId, status: Int)

object HtCondorCommands {
  val submit_output_pattern = "(\\d*) job\\(s\\) submitted to cluster (\\d*)\\."
  val condor_submit = "condor_submit"
  val condor_queue = "condor_q"
  val condor_remove = "condor_rm"
  val status_map = Map(4 -> TaskStatus(Status.Succeeded),
    2 -> TaskStatus(Status.Running),
    5 -> TaskStatus(Status.Failed),
    1 -> TaskStatus(Status.Created),
    3 -> TaskStatus(Status.Canceled))
}

object HtCondorClassAdParser

trait HtCondorCommands extends StrictLogging {

  def submitCommand(path: Path, attributes: Map[String, String]): String = {
    def htCondorSubmitCommand(filePath: Path) = {
      s"${HtCondorCommands.condor_submit} ${filePath.toString}"
    }

    val submitFileWriter = path.untailed
    attributes.foreach(attribute => submitFileWriter.writeWithNewline(s"${attribute._1}=${attribute._2}"))
    submitFileWriter.writeWithNewline(HtCondorRuntimeKeys.queue)
    submitFileWriter.writer.flushAndClose()
    logger.debug(s"submit file name is : $path")
    logger.debug(s"content of file is : ${path.lines.toList}")
    htCondorSubmitCommand(path)
  }

  def statusCommand(): String = s"${HtCondorCommands.condor_queue} -xml"
}

trait HtCondorClassAdParser extends StrictLogging {

  def xmlParser(classAd: String, condorJobId: CondorJobId): Int = {
    //This is required to turn off external validation of dtd in xml
    val factory = SAXParserFactory.newInstance()
    factory.setValidating(false)
    factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
    //Dtd validation ends here
    val listOfClassAds = withSAXParser(factory.newSAXParser()).loadString(classAd) \ HtCondorClassAdXmlTags.classTag
    val classAdMatchingClusterId = for {
      classAd <- listOfClassAds
      attribute <- classAd \ HtCondorClassAdXmlTags.attributeTag
      attributeType = (attribute \ s"@${HtCondorClassAdXmlTags.nameTag}").text
      if attributeType == HtCondorClassAdXmlTags.clusterIdTag && (attribute \ HtCondorClassAdXmlTags.intValueTag).text.toInt == condorJobId.clusterId
    } yield classAd

    logger.debug(s"classAdMatchingClusterId : ${classAdMatchingClusterId.toString()}")

    val classAdMatchingClusterAndJobId = for {
      classAd <- classAdMatchingClusterId
      attribute <- classAd \ HtCondorClassAdXmlTags.attributeTag
      attributeType = (attribute \ s"@${HtCondorClassAdXmlTags.nameTag}").text
      if attributeType == HtCondorClassAdXmlTags.jobIdTag && (attribute \ HtCondorClassAdXmlTags.intValueTag).text.toInt == condorJobId.jobId
    } yield classAd

    logger.debug(s"classAdMatchingClusterAndJobId : ${classAdMatchingClusterAndJobId.toString()}")

    val status = for {
      attribute <- classAdMatchingClusterAndJobId \ HtCondorClassAdXmlTags.attributeTag
      attributeType = (attribute \ s"@${HtCondorClassAdXmlTags.nameTag}").text
      if attributeType == HtCondorClassAdXmlTags.statusTag
    } yield (attribute \ HtCondorClassAdXmlTags.intValueTag).text.toInt

    logger.debug(s"statusNode : ${status.head}")
    status.head
  }

  def getJobStatus(classAd: String, condorJobId: CondorJobId): TaskStatus = {
    val status = xmlParser(classAd, condorJobId)
    logger.info(s"status : $status")
    HtCondorCommands.status_map(status)
  }
}

trait HtCondorProcess {
  val stdout = new StringBuilder
  val stderr = new StringBuilder

  def getProcessLogger(): ProcessLogger = ProcessLogger(stdout append _, stderr append _)
  def getProcessStdout(): String = stdout.toString().trim
  def getProcessStderr(): String = stderr.toString().trim
  def getCommandList(command: String): Seq[String] = Seq("/bin/bash",command)
  def getUntailedWriter(path: Path): UntailedWriter = path.untailed
  def getTailedWriter(limit: Int, path: Path): TailedWriter = path.tailed(limit)
  def externalProcess(cmdList: Seq[String], processLogger: ProcessLogger = getProcessLogger()): Process = cmdList.run(processLogger)
}

object HtCondorRuntimeKeys {
  val executable = "executable"
  val arguments = "arguments"
  val error = "error"
  val output = "output"
  val log = "log"
  val queue = "queue"
  val rank = "rank"
  val requirements = "requirements"
  val requestMemory = "request_memory"
  val cpu = "request_cpus"
  val disk = "request_disk"
}

object HtCondorClassAdXmlTags {
  val classAdTag = "classads"
  val classTag = "c"
  val attributeTag = "a"
  val intValueTag = "i"
  val jobIdTag = "ProcId"
  val clusterIdTag = "ClusterId"
  val statusTag = "JobStatus"
  val nameTag = "n"
}