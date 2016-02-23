package cromwell.backend.config

import org.scalatest.{Matchers, WordSpecLike}

class BackendConfigurationTest extends WordSpecLike with Matchers {
  val backendConfigurationEntry = new BackendConfigurationEntry("local", "cromwell.backend.local", Option("no.validation.class"))
  val backendConfigurationEntry2 = new BackendConfigurationEntry("acme", "cromwell.backend.acme")
  val backendList = List(backendConfigurationEntry, backendConfigurationEntry2)

  "A Backend configuration" should {
    "return default backend" in {
      val backendConfiguration = BackendConfiguration(backendList, "local")
      assert(backendConfigurationEntry.equals(backendConfiguration.defaultBackend))
    }

    "return a list of backend configuration entries" in {
      val backendConfiguration = BackendConfiguration(backendList, "local")
      assert(backendList.equals(backendConfiguration.allBackends))
    }

    "return an empty list of backend configuration entries if there is no one defined" in {
      val backendConfiguration = BackendConfiguration(List(), "local")
      assert(backendConfiguration.allBackends.isEmpty)
    }

    "throw an exception when a default backend is not defined" in {
      val backendConfiguration = BackendConfiguration(backendList, "someOther")
      intercept[IllegalStateException] {
        backendConfiguration.defaultBackend
      }
    }
  }
}
