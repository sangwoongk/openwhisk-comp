package org.apache.openwhisk.core.loadBalancer

// import scala.math

/**
 * Distribution (for function cpu utilization)
 *
 * @param maxSeries the longest series length that can be kept
 * @param initUpdateInterval the first update interval when record is empty (in seconds)
 * @param updateInterval the normal interval to update the record (in seconds)
 */
class ActionLoadRecord(
        maxHistoryLen: Int,
        initUpdateInterval: Double,
        updateInterval: Double
        ) {
    protected val _maxHistoryLen: Int = maxHistoryLen
    protected val _initUpdateInterval: Double = initUpdateInterval
    protected val _updateInterval: Double = updateInterval
    
    protected var prevUpdateTime: Long = 0
    protected var initEstimate: Boolean = true
    protected var invocations: Long = 0
    protected var rpsHistory: Array[Double] = Array.fill(_maxHistoryLen)(-1.0)
    protected var historyPointer: Int = 0
    protected var historyLen: Int = 0

    def _addRpsHistory(rps: Double) {
        rpsHistory(historyPointer) = rps
        historyPointer = historyPointer + 1
        if(historyPointer >= _maxHistoryLen)
            historyPointer = 0
        historyLen = historyLen + 1
        if(historyLen > _maxHistoryLen)
            historyLen = _maxHistoryLen
    }
    
    // return the estimated rps of the function
    def addInvocation(): (Double) =  {
        // use exeTime > 0 to filter timeout handler
        invocations = invocations + 1
        var estimated_rps: Double = -1.0
        var cur_time_ms: Long = System.currentTimeMillis()
        if(prevUpdateTime == 0)
            prevUpdateTime = cur_time_ms
        if(initEstimate && cur_time_ms - prevUpdateTime > initUpdateInterval) {
            estimated_rps = invocations*1000.0/(cur_time_ms - prevUpdateTime)
            initEstimate = false
            _addRpsHistory(estimated_rps)
            prevUpdateTime = cur_time_ms
            invocations = 0
        } else if(cur_time_ms - prevUpdateTime > updateInterval) {
            estimated_rps = invocations*1000.0/(cur_time_ms - prevUpdateTime)
            _addRpsHistory(estimated_rps)
            prevUpdateTime = cur_time_ms
            invocations = 0
        }

        estimated_rps
    }

    def showHistory(): String = {
        var s: String = ""
        var samples: Int = 0
        var s_ptr: Int = historyPointer
        while(samples <= historyLen) {
            s = s + " " + rpsHistory(s_ptr).toString
            samples = samples + 1
            s_ptr = s_ptr + 1
            if(s_ptr >= _maxHistoryLen)
                s_ptr = 0
        }
        s
    }
}