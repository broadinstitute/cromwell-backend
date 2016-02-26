package cromwell.backend.provider.htcondor

import cromwell.backend.model.{Status, TaskStatus}
import org.scalatest.{Matchers, WordSpecLike}

class HtCondorClassAdParserSpec extends WordSpecLike with Matchers {

  val xmlString =
    s"""<?xml version="1.0"?>
       <!DOCTYPE classads SYSTEM "classads.dtd">
       <classads>
       <c>
       <a n="ProcId"><i>0</i></a>
       <a n="EnteredCurrentStatus"><i>1454439245</i></a>
       <a n="ClusterId"><i>4</i></a>
       <a n="JobStatus"><i>4</i></a>
       <a n="MachineAttrSlotWeight0"><i>8</i></a>
       </c>
       <c>
       <a n="ProcId"><i>0</i></a>
       <a n="EnteredCurrentStatus"><i>1454439245</i></a>
       <a n="ClusterId"><i>8</i></a>
       <a n="JobStatus"><i>4</i></a>
       <a n="MachineAttrSlotWeight0"><i>8</i></a>
       </c>
       <c>
       <a n="ProcId"><i>1</i></a>
       <a n="EnteredCurrentStatus"><i>1454439245</i></a>
       <a n="ClusterId"><i>10</i></a>
       <a n="JobStatus"><i>2</i></a>
       <a n="MachineAttrSlotWeight0"><i>8</i></a>
       </c>
       </classads>
     """.stripMargin

  val parser = new HtCondorClassAdParser {}

  "htcondor classAd parser method" should {
    "parse given string as xml representation for jobId 0 and clusterId 8 and return status number" in {
      val status = parser.xmlParser(xmlString.trim, CondorJobId(0, 8))
      status shouldEqual 4
    }
  }

  "htcondor classAd parser method" should {
    "parse given string as xml representation for jobId 1 and clusterId 10 and return status number" in {
      val status = parser.xmlParser(xmlString.trim, CondorJobId(1, 10))
      status shouldEqual 2
    }
  }

  "htcondor classAd getJobStatus method" should {
    "for jobId 1 and clusterId 10 return task status " in {
      val status = parser.getJobStatus(xmlString.trim, CondorJobId(1, 10))
      status shouldEqual TaskStatus(Status.Running)
    }
  }
}