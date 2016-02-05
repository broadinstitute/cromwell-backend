package cromwell.backend.config

import org.scalatest.{Matchers, WordSpecLike}

class BackendConfigurationTest extends WordSpecLike with Matchers {
  val backendConfigurationEntry = new BackendConfigurationEntry("local", "cromwell.backend.local")
  val backendConfigurationEntry2 = new BackendConfigurationEntry("acme", "cromwell.backend.acme")
  val backendList = List(backendConfigurationEntry, backendConfigurationEntry2)

  "A Backend configuration" should {
    "return default backend" in {
      val backendConfiguration = new BackendConfiguration(backendList, "local")
      val actual = backendConfiguration.getDefaultBackend()
      assert(backendConfigurationEntry.equals(actual))
    }

    "return a list of backend configuration entries" in {
      val backendConfiguration = new BackendConfiguration(backendList, "local")
      val actual = backendConfiguration.getAllBackendConfigurations()
      assert(backendList.equals(actual))
    }

    "return an empty list of backend configuration entries if there is no one defined" in {
      val backendConfiguration = new BackendConfiguration(List(), "local")
      val actual = backendConfiguration.getAllBackendConfigurations()
      assert(actual.isEmpty)
    }

    "throw an exception when a default backend is not defined" in {
      val backendConfiguration = new BackendConfiguration(backendList, "someOther")
      intercept[IllegalStateException] {
        backendConfiguration.getDefaultBackend()
      }
    }
  }
}
