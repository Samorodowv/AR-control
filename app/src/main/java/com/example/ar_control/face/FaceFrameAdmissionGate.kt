package com.example.ar_control.face

class FaceFrameAdmissionGate(
    private val minimumFrameIntervalNanos: Long
) {
    private var lastAcceptedTimestampNanos: Long? = null

    @Volatile
    var acceptedCount: Long = 0L
        private set

    @Volatile
    var rejectedCount: Long = 0L
        private set

    @Synchronized
    fun shouldAccept(timestampNanos: Long): Boolean {
        val lastAccepted = lastAcceptedTimestampNanos
        if (lastAccepted == null) {
            lastAcceptedTimestampNanos = timestampNanos
            acceptedCount++
            return true
        }
        if (minimumFrameIntervalNanos <= 0L) {
            lastAcceptedTimestampNanos = timestampNanos
            acceptedCount++
            return true
        }
        if (timestampNanos < lastAccepted) {
            rejectedCount++
            return false
        }
        if (timestampNanos - lastAccepted < minimumFrameIntervalNanos) {
            rejectedCount++
            return false
        }
        lastAcceptedTimestampNanos = timestampNanos
        acceptedCount++
        return true
    }

    fun <T> runIfAccepted(timestampNanos: Long, block: () -> T): T? {
        if (!shouldAccept(timestampNanos)) {
            return null
        }
        return block()
    }
}
