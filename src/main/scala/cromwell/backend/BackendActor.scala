package cromwell.backend

import akka.actor.{Actor, ActorLogging}
import akka.event.LoggingReceive
import cromwell.backend.BackendActor._
import cromwell.backend.model.Subscription
import cromwell.caching.ExecutionHash

import scala.util._

object BackendActor {
  sealed trait BackendActorMessage
  case object Prepare extends BackendActorMessage
  case object Execute extends BackendActorMessage
  case object Stop extends BackendActorMessage
  case object CleanUp extends BackendActorMessage
  case class SubscribeToEvent[A](subscription: Subscription[A]) extends BackendActorMessage
  case class UnsubscribeToEvent[A](subscription: Subscription[A]) extends BackendActorMessage
  case object ComputeHash extends BackendActorMessage

  sealed trait ComputeHashResult extends BackendActorMessage
  final case class SuccessfulComputeHashResult(hash: ExecutionHash) extends ComputeHashResult
  final case class FailedComputeHashResult(error: Throwable) extends ComputeHashResult
}

/**
  * Defines basic structure and functionality to interact with a
  * Backend through an Akka actor system.
  * Backend methods should be implemented by each custom backend.
  */
trait BackendActor extends Backend with Actor with ActorLogging {
  private implicit val ctxt = context.dispatcher

  def receive: Receive = LoggingReceive {
    case Prepare => prepare()
    case Execute => execute()
    case Stop => stop()
    case CleanUp => cleanUp()
    case SubscribeToEvent(obj) => subscribeToEvent(obj)
    case UnsubscribeToEvent(obj) => unsubscribeToEvent(obj)
    case ComputeHash =>
      val sndr = sender()
      computeHash onComplete {
        case Success(h) => sndr ! SuccessfulComputeHashResult(h)
        case Failure(e) => sndr ! FailedComputeHashResult(e)
      }
  }
}
