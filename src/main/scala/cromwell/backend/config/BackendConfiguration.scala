package cromwell.backend.config

import com.typesafe.config.ConfigFactory
import com.typesafe.scalalogging.StrictLogging

import scala.collection.JavaConverters._
import scala.util.Try

/**
  * Defines a backend configuration.
  *
  * @param name            Backend name.
  * @param initClass       Initialization class for the specific backend.
  * @param validationClass Optional Fully qualified name of the validation class to load
  */
case class BackendConfigurationEntry(name: String, initClass: String, validationClass: Option[String] = None, backendTypeClass: Option[String] = None)

object BackendConfiguration {
  val BackendConfig = ConfigFactory.load().getConfig("backend")
  val DefaultBackendName = BackendConfig.getString("default")
  val BackendProviders = BackendConfig.getConfigList("providers").asScala.toList
  val BackendList = BackendProviders.map(entry => BackendConfigurationEntry(
    entry.getString("name"),
    entry.getString("initClass"),
    Try(entry.getString("validationClass")).toOption,
    Try(entry.getString("backendTypeClass")).toOption))

  def apply(): BackendConfiguration = new BackendConfiguration(BackendList, DefaultBackendName)
}

/**
  * Retrieves backend configuration.
  *
  * @param allBackends  List of backend entries in configuration file.
  * @param defaultBackendName        Backend name to be used as default.
  */
case class BackendConfiguration private[config](allBackends: List[BackendConfigurationEntry], defaultBackendName: String) extends StrictLogging {
  import BackendConfiguration._
  /**
    * Gets default backend configuration. There will be always just one default backend defined in configuration file.
    * It lookup for the backend definition which contains the name defined in 'default' entry in backend configuration.
    *
    * @return Backend configuration.
    */
  def defaultBackend: BackendConfigurationEntry = allBackends find { _.name == defaultBackendName } getOrElse {
    val errMsg = "Default backend configuration was not found."
    logger.error(errMsg)
    throw new IllegalStateException(errMsg)
  }
}
