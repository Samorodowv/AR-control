package com.example.ar_control.face

class FaceFrameAdmissionGate(
    private val minimumFrameIntervalNanos: Long
) {
    private var lastAcceptedTimestampNanos: Long? = null

    @Synchronized
    fun shouldAccept(timestampNanos: Long): Boolean {
        val lastAccepted = lastAcceptedTimestampNanos
        if (lastAccepted == null) {
            lastAcceptedTimestampNanos = timestampNanos
            return true
        }
        if (minimumFrameIntervalNanos <= 0L) {
            lastAcceptedTimestampNanos = timestampNanos
            return true
        }
        if (timestampNanos < lastAccepted) {
            return false
        }
        if (timestampNanos - lastAccepted < minimumFrameIntervalNanos) {
            return false
        }
        lastAcceptedTimestampNanos = timestampNanos
        return true
    }

    fun <T> runIfAccepted(timestampNanos: Long, block: () -> T): T? {
        if (!shouldAccept(timestampNanos)) {
            return null
        }
        return block()
    }
}
