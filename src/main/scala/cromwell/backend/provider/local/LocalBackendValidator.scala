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
    * @return `ValidateResult` to indicate a Yay! or a Nay!
    */
  override def validateWorkflow: Future[ValidationResult] = {
    import scala.concurrent.ExecutionContext.Implicits.global
    //Do something meaningful here, don't know what yet. Currently it's mostly for demo purposes
    Future(SuccessfulValidationResult)
  }
}
