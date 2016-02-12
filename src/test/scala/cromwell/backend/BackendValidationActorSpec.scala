package cromwell.backend

import akka.actor.{ActorSystem, Props}
import akka.testkit.{ImplicitSender, TestKit}
import cromwell.backend.BackendValidationActor.{FailedValidationResult, SuccessfulValidationResult, Validate, ValidationResult}
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpecLike}
import wdl4s._

import scala.concurrent.ExecutionContext.Implicits.global
import scala.concurrent.Future

class BackendValidationActorSpec extends TestKit(ActorSystem("BackendValidationActorSpec"))
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterAll
  with ImplicitSender {

  val helloWorldWdl =
    """
      |task hello {
      |  command {
      |    echo "Hello World!"
      |  }
      |  output {
      |    String salutation = read_string(stdout())
      |  }
      |  runtime {
      |   docker: "some docker"
      |  }
      |}
      |
      |workflow hello {
      |  call hello
      |}
    """.stripMargin

  class SomeConcreteBackend(shouldThrow: Boolean = false) extends BackendValidationActor {
    override def validateWorkflow(namespace: NamespaceWithWorkflow, wfInputs: Option[WorkflowCoercedInputs] = None, wfOptionsJson: Option[String] = None): Future[ValidationResult] = Future {
      shouldThrow match {
        case false => SuccessfulValidationResult
        case true => throw new IllegalStateException("Some exception...")
      }
    }
  }

  "BackendValidationActor" must {
    "return a SuccessfulValidationResult" in {
      val backend = system.actorOf(Props(new SomeConcreteBackend()))
      //new SomeConcreteBackend("Yes it fails!", true)
      backend ! Validate(NamespaceWithWorkflow.load(helloWorldWdl))
      expectMsg(SuccessfulValidationResult)
      system.stop(backend)
    }
    "return a FailedValidationResult in case the call to validateWorkflow throws Exception" in {
      val backend = system.actorOf(Props(new SomeConcreteBackend(true)))
      //new SomeConcreteBackend("Yes it fails!", true)
      backend ! Validate(NamespaceWithWorkflow.load(helloWorldWdl))
      expectMsgClass(classOf[FailedValidationResult])
      system.stop(backend)
    }
  }

  override def afterAll(): Unit = system.shutdown()
}
