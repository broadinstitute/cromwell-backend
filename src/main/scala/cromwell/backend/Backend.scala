package cromwell.backend

import cromwell.backend.model.{ExecutionHash, Subscription}

import scala.concurrent.Future

/**
  * Defines basic functionality to interact with a Backend.
  */
trait Backend {
  /**
    * Prepare the task and context for execution.
    */
  def prepare(): Unit

  /**
    * Executes task in given context.
    */
  def execute(): Unit

  /**
    * Stops a task execution.
    */
  def stop(): Unit

  /**
    * Performs a cleanUp after the task was executed.
    */
  def cleanUp(): Unit

  /**
    * Subscribe to events on backend.
    */
  def subscribeToEvent[A](subscription: Subscription[A]): Unit

  /**
    * Unsubscribe to events on backend.
    */
  def unsubscribeToEvent[A](subscription: Subscription[A]): Unit

  /**
    * Returns hash based on TaskDescriptor attributes.
    *
    * @return Return hash for related task.
    */
  def computeHash: Future[ExecutionHash]
}
