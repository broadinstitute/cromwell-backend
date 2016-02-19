package cromwell.backend.provider.local

import cromwell.backend.BackendValidationActor
import cromwell.backend.BackendValidationActor.{SuccessfulValidationResult, ValidationResult}
import wdl4s.{WorkflowCoercedInputs, NamespaceWithWorkflow}

import scala.concurrent.Future

class LocalBackendValidator(override val namespace: NamespaceWithWorkflow,
                            override val wfInputs: Option[WorkflowCoercedInputs] = None,
                            override val wfOptions: Option[String] = None) extends BackendValidationActor {
  /**
    *
    * @return True (wrapped in a message) to indicate a Yay!
    */
  override def validateWorkflow: Future[ValidationResult] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    Future(SuccessfulValidationResult)
  }
}
