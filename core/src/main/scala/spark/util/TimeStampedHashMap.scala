package spark.util

import java.util.concurrent.ConcurrentHashMap
import scala.collection.JavaConversions
import scala.collection.mutable.Map

/**
 * This is a custom implementation of scala.collection.mutable.Map which stores the insertion
 * time stamp along with each key-value pair. Key-value pairs that are older than a particular
 * threshold time can them be removed using the clearOldValues method. This is intended to be a drop-in
 * replacement of scala.collection.mutable.HashMap.
 */
class TimeStampedHashMap[A, B] extends Map[A, B]() with spark.Logging {
  val internalMap = new ConcurrentHashMap[A, (B, Long)]()

  def get(key: A): Option[B] = {
    val value = internalMap.get(key)
    if (value != null) Some(value._1) else None
  }

  def iterator: Iterator[(A, B)] = {
    val jIterator = internalMap.entrySet().iterator()
    JavaConversions.asScalaIterator(jIterator).map(kv => (kv.getKey, kv.getValue._1))
  }

  override def + [B1 >: B](kv: (A, B1)): Map[A, B1] = {
    val newMap = new TimeStampedHashMap[A, B1]
    newMap.internalMap.putAll(this.internalMap)
    newMap.internalMap.put(kv._1, (kv._2, currentTime))
    newMap
  }

  override def - (key: A): Map[A, B] = {
    val newMap = new TimeStampedHashMap[A, B]
    newMap.internalMap.putAll(this.internalMap)
    newMap.internalMap.remove(key)
    newMap
  }

  override def += (kv: (A, B)): this.type = {
    internalMap.put(kv._1, (kv._2, currentTime))
    this
  }

  override def -= (key: A): this.type = {
    internalMap.remove(key)
    this
  }

  override def update(key: A, value: B) {
    this += ((key, value))
  }

  override def apply(key: A): B = {
    val value = internalMap.get(key)
    if (value == null) throw new NoSuchElementException()
    value._1
  }

  override def filter(p: ((A, B)) => Boolean): Map[A, B] = {
    JavaConversions.asScalaConcurrentMap(internalMap).map(kv => (kv._1, kv._2._1)).filter(p)
  }

  override def empty: Map[A, B] = new TimeStampedHashMap[A, B]()

  override def size: Int = internalMap.size

  override def foreach[U](f: ((A, B)) => U) {
    val iterator = internalMap.entrySet().iterator()
    while(iterator.hasNext) {
      val entry = iterator.next()
      val kv = (entry.getKey, entry.getValue._1)
      f(kv)
    }
  }

  /**
   * Removes old key-value pairs that have timestamp earlier than `threshTime`
   */
  def clearOldValues(threshTime: Long) {
    val iterator = internalMap.entrySet().iterator()
    while(iterator.hasNext) {
      val entry = iterator.next()
      if (entry.getValue._2 < threshTime) {
        logDebug("Removing key " + entry.getKey)
        iterator.remove()
      }
    }
  }

  private def currentTime: Long = System.currentTimeMillis()

}
