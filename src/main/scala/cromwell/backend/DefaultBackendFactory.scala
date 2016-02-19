package cromwell.backend

import akka.actor.{Props, ActorRef, ActorSystem}

object DefaultBackendFactory extends BackendFactory {
  /**
    * Returns an actor reference related to the Backend
    *
    * @param initClass   Fully qualified class name of the backend to instantiate
    * @param actorSystem Cromwell Engine ActorSystem
    * @param args        The list of arguments that the class expects
    * @return A backend actor reference corresponding to the initClass
    */
  override def getBackendActorFor(initClass: String, actorSystem: ActorSystem, args: AnyRef*): ActorRef = {
    val backendActorProps = Props.create(Class.forName(initClass), args: _*)
    actorSystem.actorOf(backendActorProps)
  }
}
