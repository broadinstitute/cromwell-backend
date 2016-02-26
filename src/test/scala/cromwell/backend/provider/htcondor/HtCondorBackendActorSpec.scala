package cromwell.backend.provider.htcondor

import java.io.Writer
import java.nio.file.{Files, Path, Paths}
import akka.actor.{ActorRef, Actor, ActorSystem}
import akka.testkit.{TestProbe, TestActorRef, ImplicitSender, TestKit}
import cromwell.backend.BackendActor.{Execute, SubscribeToEvent}
import cromwell.backend.model._
import cromwell.backend.provider.local.{PathWriter, TailedWriter, UntailedWriter}
import org.scalatest.mock.MockitoSugar
import org.mockito.Mockito._
import org.mockito.Matchers._
import org.scalatest.{Matchers, BeforeAndAfterAll, WordSpecLike}
import wdl4s.NamespaceWithWorkflow
import better.files._, Cmds._
import scala.sys.process.{Process, ProcessLogger}

class HtCondorBackendActorSpec extends TestKit(ActorSystem("HtCondorBackendActorSpec"))
  with WordSpecLike
  with Matchers
  with MockitoSugar
  with BeforeAndAfterAll
  with ImplicitSender {

  override def afterAll(): Unit = {
    stdout.delete()
    stderr.delete()
    rc.delete()
    executionDir.delete(true)
    tempDir.delete(true)
    system.shutdown()
  }

  val htCondorParser = new HtCondorClassAdParser {}
  val htCondorCommands = new HtCondorCommands {}
  val htCondorProcess = mock[HtCondorProcess]
  val stdoutResult =
    s"""<?xml version="1.0"?>
       <!DOCTYPE classads SYSTEM "classads.dtd">
       <classads>
       <c>
       <a n="ProcId"><i>2</i></a>
       <a n="EnteredCurrentStatus"><i>1454439245</i></a>
       <a n="ClusterId"><i>88</i></a>
       <a n="JobStatus"><i>4</i></a>
       <a n="MachineAttrSlotWeight0"><i>8</i></a>
       </c>
       </classads>
     """.stripMargin

  val helloWorldWdl =
    """
      |task hello {
      |  command {
      |    echo "Hello World!"
      |  }
      |  output {
      |    String salutation = read_string(stdout())
      |  }
      |}
      |
      |workflow hello {
      |  call hello
      |}
    """.stripMargin

  val ns = NamespaceWithWorkflow.load(helloWorldWdl)
  val decl = ns.workflow.declarations
  val commandTemplate = ns.findTask("hello").get.commandTemplate
  val tempDir = File.newTempDir()
  val executionDir = Paths.get(tempDir.path.toString, "test", "1").toString.toFile.createIfNotExists(true)
  val output = ns.findTask("hello").get.outputs

  val stdout = Paths.get(executionDir.path.toString(), "stdout")
  val probe = TestProbe()
  stdout.toString.toFile.createIfNotExists(false)
  stdout <<
    """Submitting job(s)..
      |2 job(s) submitted to cluster 88.
    """.stripMargin.trim

  val stderr = Paths.get(executionDir.path.toString, "stderr")
  stderr.toString.toFile.createIfNotExists(false)

  val rc = Paths.get(executionDir.path.toString, "rc")
  rc.toString.toFile.createIfNotExists(false)
  rc << s"""0""".stripMargin.trim

  val task = TaskDescriptor(name = "test", index = Option(1), user = "test",
    commandTemplate = commandTemplate, declarations = decl,
    workingDir = tempDir.path.toString(), inputs = Map(),
    outputs = output, runtimeAttributes = Map())

  val actorRef = TestActorRef(new Actor {
    var dest: ActorRef = _
    def receive = {
      case Subscribed => dest ! Subscribed
      case Unsubscribed => dest ! Unsubscribed
      case result@TaskStatus(status) => dest ! result
      case response@TaskFinalStatus(status, result) => dest ! response
      case (probe: ActorRef) => dest = probe
    }
  })

  trait MockWriter extends Writer {
    var closed = false
    override def close = closed = true
    override def flush = { }
    override def write(a: Array[Char], b: Int, c: Int) = {}
  }

  trait MockPathWriter extends PathWriter {
    override val path: Path = mock[Path]
    override lazy val writer : Writer = new MockWriter {}
  }

  "executeTask method" should {
    "return succeeded task status with stdout " in {
      val backend = system.actorOf(HtCondorBackendActor.props(task, htCondorCommands, htCondorParser, htCondorProcess))

      actorRef ! probe.ref
      backend ! SubscribeToEvent(Subscription(new ExecutionEvent, actorRef))
      probe.expectMsg(Subscribed)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(stdout) with MockPathWriter
      val stubTailed = new TailedWriter(stderr, 100) with MockPathWriter
      val stderrResult = ""

      when(htCondorProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(htCondorProcess.getTailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(htCondorProcess.getUntailedWriter(any[Path])).thenReturn(stubUntailed)
      when(htCondorProcess.getProcessStdout()).thenReturn(stdoutResult)
      when(htCondorProcess.getProcessStderr()).thenReturn(stderrResult)

      backend ! Execute
      probe.expectMsg(TaskStatus(Status.Running))
      probe.expectMsg(TaskStatus(Status.Succeeded))
      probe.expectMsgClass(classOf[TaskFinalStatus])

      verify(htCondorProcess,times(2)).externalProcess(any[Seq[String]], any[ProcessLogger])
      verify(htCondorProcess,times(1)).getTailedWriter(any[Int], any[Path])
      verify(htCondorProcess,times(1)).getUntailedWriter(any[Path])
    }
  }

  "executeTask method" should {
    "return failed task status with stderr " in {
      val backend = system.actorOf(HtCondorBackendActor.props(task, htCondorCommands, htCondorParser, htCondorProcess))
      stderr <<
        s"""
           |test
         """.stripMargin
      actorRef ! probe.ref
      backend ! SubscribeToEvent(Subscription(new ExecutionEvent, actorRef))
      probe.expectMsg(Subscribed)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(stdout) with MockPathWriter
      val stubTailed = new TailedWriter(stderr, 100) with MockPathWriter
      val stderrResult = ""

      when(htCondorProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(0)
      when(htCondorProcess.getTailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(htCondorProcess.getUntailedWriter(any[Path])).thenReturn(stubUntailed)
      when(htCondorProcess.getProcessStdout()).thenReturn(stdoutResult)
      when(htCondorProcess.getProcessStderr()).thenReturn(stderrResult)

      backend ! Execute
      probe.expectMsg(TaskStatus(Status.Running))
      probe.expectMsg(TaskStatus(Status.Failed))
      probe.expectMsgClass(classOf[TaskFinalStatus])
    }
  }

  "executeTask method" should {
    "return failed task status with stderr on non-zero process exit " in {
      val backend = system.actorOf(HtCondorBackendActor.props(task, htCondorCommands, htCondorParser, htCondorProcess))
      actorRef ! probe.ref
      backend ! SubscribeToEvent(Subscription(new ExecutionEvent, actorRef))
      probe.expectMsg(Subscribed)
      val stubProcess = mock[Process]
      val stubUntailed = new UntailedWriter(stdout) with MockPathWriter
      val stubTailed = new TailedWriter(stderr, 100) with MockPathWriter
      val stderrResult = ""

      when(htCondorProcess.externalProcess(any[Seq[String]], any[ProcessLogger])).thenReturn(stubProcess)
      when(stubProcess.exitValue()).thenReturn(-1)
      when(htCondorProcess.getTailedWriter(any[Int], any[Path])).thenReturn(stubTailed)
      when(htCondorProcess.getUntailedWriter(any[Path])).thenReturn(stubUntailed)
      when(htCondorProcess.getProcessStdout()).thenReturn(stdoutResult)
      when(htCondorProcess.getProcessStderr()).thenReturn(stderrResult)

      backend ! Execute
      probe.expectMsg(TaskStatus(Status.Running))
      probe.expectMsg(TaskStatus(Status.Failed))
      probe.expectMsgClass(classOf[TaskFinalStatus])
    }
  }
}