package cromwell.backend

import akka.actor.ActorSystem
import akka.testkit.{ImplicitSender, TestKit}
import cromwell.backend.model.TaskDescriptor
import org.scalatest.{BeforeAndAfterAll, MustMatchers, WordSpecLike}
import org.scalatest.mock.MockitoSugar
import wdl4s.NamespaceWithWorkflow

class DefaultBackendFactoryRef extends TestKit(ActorSystem("DefaultBackendFactorySpec"))
  with WordSpecLike
  with MustMatchers
  with BeforeAndAfterAll
  with ImplicitSender
  with MockitoSugar {

  val localBackendInitClass = "cromwell.backend.provider.local.LocalBackend"
  val localBackendValidatorClass = "cromwell.backend.provider.local.LocalBackendValidator"

  "DefaultBackendFactory" should {
    "return valid LocalBackendActor for a given TaskDesc" in {
      val taskDescMock =  mock[TaskDescriptor]
      val actRef = Option(DefaultBackendFactory.getBackendActorFor(localBackendInitClass, system, taskDescMock))
      assert(actRef.isDefined)
    }
    "return a valid LocalBackendValidator" in {
      val wfWithNamespaceMock = mock[NamespaceWithWorkflow]
      val wfCoercedInputsMock = None
      val wfOptions = Option("someWfOptions")
      val actRef = Option(DefaultBackendFactory.getBackendActorFor(localBackendValidatorClass, system, wfWithNamespaceMock, wfCoercedInputsMock, wfOptions))
      assert(actRef.isDefined)
    }
  }

  override def afterAll(): Unit = system.shutdown()
}
