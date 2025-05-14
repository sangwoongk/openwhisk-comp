package org.apache.openwhisk.core.containerpool

import scala.collection.mutable.Map
import org.apache.openwhisk.common.TransactionId

object CpuCounter {
  var transidBuffer = Map.empty[TransactionId, Double]
  var reqCpu = 0.0
  var memCount = 0.0
}
