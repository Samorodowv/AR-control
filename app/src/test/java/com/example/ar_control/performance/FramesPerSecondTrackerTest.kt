package com.example.ar_control.performance

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FramesPerSecondTrackerTest {

    @Test
    fun recordFrame_returnsNullUntilEnoughSamplesExist() {
        val tracker = FramesPerSecondTracker(windowNanos = 1_000_000_000L)

        assertNull(tracker.recordFrame(0L))
        assertNull(tracker.recordFrame(500_000_000L))
    }

    @Test
    fun recordFrame_returnsRollingFramesPerSecond() {
        val tracker = FramesPerSecondTracker(windowNanos = 1_000_000_000L)

        tracker.recordFrame(0L)
        tracker.recordFrame(500_000_000L)
        val fps = tracker.recordFrame(1_000_000_000L)

        assertEquals(2f, fps ?: 0f, 0.0001f)
    }

    @Test
    fun reset_clearsPreviousSamples() {
        val tracker = FramesPerSecondTracker(windowNanos = 1_000_000_000L)

        tracker.recordFrame(0L)
        tracker.recordFrame(500_000_000L)
        tracker.reset()

        assertNull(tracker.recordFrame(2_000_000_000L))
    }
}
