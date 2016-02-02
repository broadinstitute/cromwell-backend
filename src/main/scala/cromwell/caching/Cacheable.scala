package cromwell.caching

import cromwell.caching.caching.Md5sum

/**
  * Provides basic definition to work with TaskDescriptor hashes.
  */
trait Cacheable {

  /**
    * Returns hash based on TaskDescriptor attributes.
    * @return Return hash for related task.
    */
  def computeHash: Md5sum

}
