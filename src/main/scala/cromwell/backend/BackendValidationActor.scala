package cromwell.backend

import akka.actor.{Actor, ActorLogging}
import akka.event.LoggingReceive
import cromwell.backend.BackendValidationActor.{FailedValidationResult, Validate, ValidationResult}
import wdl4s.{NamespaceWithWorkflow, WorkflowCoercedInputs}

import scala.concurrent.Future

object BackendValidationActor {
  sealed trait BackendValidationActorMessage
  final case class Validate(namespace: NamespaceWithWorkflow, wfInputs: Option[WorkflowCoercedInputs] = None, wfOptionsJson: Option[String] = None) extends BackendValidationActorMessage
  sealed trait ValidationResult extends BackendValidationActorMessage
  final case class FailedValidationResult(errors: List[String]) extends ValidationResult
  case object SuccessfulValidationResult extends ValidationResult
}

/**
  * Note: This receives a whole workflow as opposed to task at a time methodology which the other parts of this component stick to.
  * It should be made uniform about what it is that we do, i.e. WF at a time or Task at a time. This is currently to support
  * validations in the JES Backend.
  *
  * It should be assumed that the basic WF validation, such as input coercion or namespace validation has already been performed
  *
  * Will validate the following things against this backend:
  * 1.) The runtime attributes
  * 2.) The workflow options
  * 3.) Optionally, validate the inputs (if passed along)
  */
trait BackendValidationActor extends Actor with ActorLogging {
  import scala.concurrent.ExecutionContext.Implicits.global

  /**
    *
    * @param namespace     Represent a directly runnable WDL Namespace
    * @param wfInputs      Workflow options specified as a Json String
    * @param wfOptionsJson Workflow options specified as a Json String
    * @return True (wrapped in a message) to indicate a Yay!
    */
  def validateWorkflow(namespace: NamespaceWithWorkflow, wfInputs: Option[WorkflowCoercedInputs] = None, wfOptionsJson: Option[String] = None): Future[ValidationResult]

  //We don't want subclasses to modify this behavior
  final def receive: Receive = LoggingReceive {
    case Validate(namespace, inputsJson, optionsJson) =>
      val requester = sender()
      validateWorkflow(namespace, inputsJson, optionsJson) map {
        requester ! _
      } recover {
        // The call to validateWorkflow resulted in an exception
        case exception: Throwable =>
          requester ! FailedValidationResult(List(s"Failed to validate the workflow: ${exception.getMessage}"))
      }
    case unknownMessage => log.error(s"BackendValidationActor received an unknown message: ${unknownMessage}")
  }
}
