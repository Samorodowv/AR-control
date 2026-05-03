package com.example.ar_control.performance

class FramesPerSecondTracker(
    private val windowNanos: Long = DEFAULT_WINDOW_NANOS,
    private val minimumSampleCount: Int = DEFAULT_MINIMUM_SAMPLE_COUNT
) {

    private val frameTimestampsNanos = ArrayDeque<Long>()

    fun reset() {
        frameTimestampsNanos.clear()
    }

    fun recordFrame(timestampNanos: Long): Float? {
        frameTimestampsNanos.addLast(timestampNanos)
        trimExpiredSamples(timestampNanos)

        if (frameTimestampsNanos.size < minimumSampleCount) {
            return null
        }

        val firstTimestamp = frameTimestampsNanos.first()
        val lastTimestamp = frameTimestampsNanos.last()
        val durationNanos = lastTimestamp - firstTimestamp
        if (durationNanos <= 0L) {
            return null
        }

        return ((frameTimestampsNanos.size - 1).toFloat() * NANOS_PER_SECOND) / durationNanos.toFloat()
    }

    private fun trimExpiredSamples(latestTimestampNanos: Long) {
        while (frameTimestampsNanos.size > minimumSampleCount) {
            val oldestTimestamp = frameTimestampsNanos.first()
            if ((latestTimestampNanos - oldestTimestamp) <= windowNanos) {
                return
            }
            frameTimestampsNanos.removeFirst()
        }
    }

    private companion object {
        const val DEFAULT_WINDOW_NANOS = 1_000_000_000L
        const val DEFAULT_MINIMUM_SAMPLE_COUNT = 3
        const val NANOS_PER_SECOND = 1_000_000_000f
    }
}
