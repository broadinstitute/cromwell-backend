package cromwell.backend

import akka.actor.{ActorRef, ActorSystem}

/**
  * Creates Backend instances based on backend name, actor system and task.
  */
trait BackendFactory {

  /**
    * Returns an actor reference related to the Backend
    * @param initClass Fully qualified class name of the backend to instantiate
    * @param actorSystem Cromwell Engine ActorSystem
    * @param args The list of arguments that the class expects
    * @return A backend actor reference corresponding to the initClass
    */
  def getBackendActorFor(initClass: String, actorSystem: ActorSystem, args: AnyRef*): ActorRef
}
