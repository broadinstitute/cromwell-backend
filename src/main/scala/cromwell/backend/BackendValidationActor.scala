package cromwell.backend

import akka.actor.{Actor, ActorLogging}
import akka.event.LoggingReceive
import cromwell.backend.BackendValidationActor.{Validate, ValidationResult}
import wdl4s.NamespaceWithWorkflow

import scala.concurrent.Future

object BackendValidationActor {
  sealed trait BackendValidationActorMessage
  case class Validate(namespace: NamespaceWithWorkflow, wfInputsJson: Option[String] = None, wfOptionsJson: String) extends BackendValidationActorMessage
  case class ValidationResult(isSuccess: Boolean) extends BackendValidationActorMessage
}

/**
  * Note: This receives a whole workflow as opposed to task at a time methodology which the other parts of this component stick to.
  *       It should be made uniform about what it is that we do, i.e. WF at a time or Task at a time. This is currently to support
  *       validations in the JES Backend.
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
    * @param namespace Represent a directly runnable WDL Namespace
    * @param wfInputsJson Workflow options specified as a Json String
    * @param wfOptionsJson Workflow options specified as a Json String
    * @return True (wrapped in a message) to indicate a Yay!
    */
  def validateWorkflow(namespace: NamespaceWithWorkflow, wfInputsJson: Option[String] = None, wfOptionsJson: String): Future[ValidationResult]

  //We don't want sub classes to modify this behavior
  final def receive: Receive = LoggingReceive {
    case Validate(namespace, inputsJson, optionsJson) =>
      val requester = sender()
      validateWorkflow(namespace, inputsJson, optionsJson) map {
        requester ! _
      }
    case unknownMessage => log.error(s"BackendValidationActor received an unknown message: ${unknownMessage}")
  }
}