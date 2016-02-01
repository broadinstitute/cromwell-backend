package cromwell.caching

import cromwell.backend.model.TaskDescriptor
import cromwell.caching.caching.Md5sum

/**
  * Provides basic definition to work with TaskDescriptor hashes.
  */
trait Cacheable {

  /**
    * Returns hash based on TaskDescriptor attributes.
    * @param task Task attributes.
    * @return Return hash for related task.
    */
  def computeHash(task: TaskDescriptor): Md5sum

}
