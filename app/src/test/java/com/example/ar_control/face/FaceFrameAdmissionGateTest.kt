package com.example.ar_control.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class FaceFrameAdmissionGateTest {
    @Test
    fun shouldAccept_allowsFirstFrameAndFramesAfterInterval() {
        val gate = FaceFrameAdmissionGate(minimumFrameIntervalNanos = 250_000_000L)

        assertTrue(gate.shouldAccept(0L))
        assertFalse(gate.shouldAccept(100_000_000L))
        assertFalse(gate.shouldAccept(249_999_999L))
        assertTrue(gate.shouldAccept(250_000_000L))
        assertFalse(gate.shouldAccept(300_000_000L))
        assertTrue(gate.shouldAccept(500_000_000L))
    }

    @Test
    fun shouldAccept_rejectsOutOfOrderFrames() {
        val gate = FaceFrameAdmissionGate(minimumFrameIntervalNanos = 250_000_000L)

        assertTrue(gate.shouldAccept(500_000_000L))
        assertFalse(gate.shouldAccept(250_000_000L))
    }

    @Test
    fun shouldAccept_withZeroIntervalAcceptsEveryTimestamp() {
        val gate = FaceFrameAdmissionGate(minimumFrameIntervalNanos = 0L)

        assertAcceptedAndLastTimestamp(gate, 100L)
        assertAcceptedAndLastTimestamp(gate, 50L)
        assertAcceptedAndLastTimestamp(gate, 50L)
        assertAcceptedAndLastTimestamp(gate, 25L)
    }

    @Test
    fun shouldAccept_withNegativeIntervalAcceptsEveryTimestamp() {
        val gate = FaceFrameAdmissionGate(minimumFrameIntervalNanos = -1L)

        assertAcceptedAndLastTimestamp(gate, 100L)
        assertAcceptedAndLastTimestamp(gate, 50L)
        assertAcceptedAndLastTimestamp(gate, 50L)
        assertAcceptedAndLastTimestamp(gate, 25L)
    }

    @Test
    fun runIfAccepted_doesNotInvokeBlockForRejectedFrames() {
        val gate = FaceFrameAdmissionGate(minimumFrameIntervalNanos = 250_000_000L)
        var acceptedInvocations = 0
        var rejectedInvocations = 0

        val acceptedResult = gate.runIfAccepted(0L) {
            acceptedInvocations++
            "copied"
        }
        val rejectedResult = gate.runIfAccepted(100_000_000L) {
            rejectedInvocations++
            "should-not-copy"
        }

        assertEquals("copied", acceptedResult)
        assertNull(rejectedResult)
        assertEquals(1, acceptedInvocations)
        assertEquals(0, rejectedInvocations)
    }

    @Test
    fun shouldAccept_allowsOnlyOneConcurrentFrameAtSameTimestamp() {
        repeat(10) {
            val acceptedCount = countConcurrentAcceptances(
                gate = FaceFrameAdmissionGate(minimumFrameIntervalNanos = 250_000_000L),
                timestampNanos = 0L,
                threadCount = 128
            )

            assertEquals(1, acceptedCount)
        }
    }

    private fun countConcurrentAcceptances(
        gate: FaceFrameAdmissionGate,
        timestampNanos: Long,
        threadCount: Int
    ): Int {
        val ready = CountDownLatch(threadCount)
        val start = CountDownLatch(1)
        val done = CountDownLatch(threadCount)
        val acceptedCount = AtomicInteger()
        val executor = Executors.newFixedThreadPool(threadCount)
        try {
            repeat(threadCount) {
                executor.execute {
                    try {
                        ready.countDown()
                        start.await()
                        if (gate.shouldAccept(timestampNanos)) {
                            acceptedCount.incrementAndGet()
                        }
                    } finally {
                        done.countDown()
                    }
                }
            }

            assertTrue(ready.await(5, TimeUnit.SECONDS))
            start.countDown()
            assertTrue(done.await(5, TimeUnit.SECONDS))
            return acceptedCount.get()
        } finally {
            executor.shutdownNow()
        }
    }

    private fun assertAcceptedAndLastTimestamp(
        gate: FaceFrameAdmissionGate,
        timestampNanos: Long
    ) {
        assertTrue(gate.shouldAccept(timestampNanos))
        assertEquals(timestampNanos, gate.lastAcceptedTimestampNanos())
    }

    private fun FaceFrameAdmissionGate.lastAcceptedTimestampNanos(): Long? {
        val field = FaceFrameAdmissionGate::class.java.getDeclaredField("lastAcceptedTimestampNanos")
        field.isAccessible = true
        return field.get(this) as Long?
    }
}
