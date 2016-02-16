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
case class BackendConfigurationEntry(name: String, initClass: String, validationClass: Option[String] = None)

object BackendConfiguration {
  val config = ConfigFactory.load()
  val backendCfg = config.getConfig("backend")
  val defaultBackend = backendCfg.getString("default")
  val backendProviders = backendCfg.getConfigList("providers").asScala.toList
  val backendList = backendProviders.map(entry =>
    BackendConfigurationEntry(entry.getString("name"), entry.getString("initClass"), Try(entry.getString("validationClass")).toOption))

  def apply(): BackendConfiguration = new BackendConfiguration(backendList, defaultBackend)
}

/**
  * Retrieves backend configuration.
  *
  * @param backendList    List of backend entries in configuration file.
  * @param defaultBackend Backend name to be used as default.
  */
class BackendConfiguration(backendList: List[BackendConfigurationEntry], defaultBackend: String) extends StrictLogging {
  /**
    * Gets default backend configuration. There will be always just one default backend defined in configuration file.
    * It lookup for the backend definition which contains the name defined in 'default' entry in backend configuration.
    *
    * @return Backend configuration.
    */
  def getDefaultBackend(): BackendConfigurationEntry = backendList find {
    _.name.equals(defaultBackend)
  } getOrElse {
    val errMsg = "Default backend configuration was not found."
    logger.error(errMsg)
    throw new IllegalStateException(errMsg)
  }

  /**
    * Gets all backend configurations from config file.
    *
    * @return
    */
  def getAllBackendConfigurations(): List[BackendConfigurationEntry] = backendList

}
