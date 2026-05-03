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
    fun shouldAccept_tracksAcceptedAndRejectedCounts() {
        val gate = FaceFrameAdmissionGate(minimumFrameIntervalNanos = 250_000_000L)

        assertTrue(gate.shouldAccept(0L))
        assertEquals(1, gate.acceptedCount)
        assertEquals(0, gate.rejectedCount)

        assertFalse(gate.shouldAccept(100_000_000L))
        assertEquals(1, gate.acceptedCount)
        assertEquals(1, gate.rejectedCount)

        assertTrue(gate.shouldAccept(250_000_000L))
        assertEquals(2, gate.acceptedCount)
        assertEquals(1, gate.rejectedCount)
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
        assertEquals(1, gate.acceptedCount)
        assertEquals(1, gate.rejectedCount)
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

    @Test
    fun diagnosticLogGate_reportsEveryCrossedThresholdWithoutDuplicates() {
        val gate = FacePipelineDiagnosticLogGate(logEveryAcceptedFrames = 30L)

        assertEquals(emptyList<Long>(), gate.crossedThresholds(0L))
        assertEquals(emptyList<Long>(), gate.crossedThresholds(29L))
        assertEquals(listOf(30L, 60L), gate.crossedThresholds(61L))
        assertEquals(emptyList<Long>(), gate.crossedThresholds(61L))

        val gateAfterThirty = FacePipelineDiagnosticLogGate(logEveryAcceptedFrames = 30L)

        assertEquals(listOf(30L), gateAfterThirty.crossedThresholds(30L))
        assertEquals(listOf(60L, 90L), gateAfterThirty.crossedThresholds(91L))
        assertEquals(emptyList<Long>(), gateAfterThirty.crossedThresholds(91L))
    }

    @Test
    fun faceRecognitionFailureState_clearsRecognitionPayloadAndKeepsFrameCounts() {
        val state = faceRecognitionFailureState(
            acceptedFrameCount = 7L,
            rejectedFrameCount = 5L
        )

        assertTrue(state.modelReady)
        assertEquals(0, state.faceCount)
        assertNull(state.bestFaceEmbedding)
        assertNull(state.matchedFace)
        assertEquals(emptyList<FaceBoundingBox>(), state.faceBoxes)
        assertEquals(FaceRecognitionStatus.NoFace, state.status)
        assertEquals(0L, state.lastDetectionMillis)
        assertEquals(0L, state.lastEmbeddingMillis)
        assertEquals(7L, state.acceptedFrameCount)
        assertEquals(5L, state.rejectedFrameCount)
    }

    @Test
    fun faceStatePublicationGate_skipsOlderTokensAfterNewerPublication() {
        val gate = FaceStatePublicationGate()
        val emittedTokens = mutableListOf<Long>()

        assertFalse(gate.emitIfCurrent(0L) { emittedTokens += 0L })
        val firstToken = gate.nextToken()
        assertTrue(gate.emitIfCurrent(firstToken) { emittedTokens += firstToken })

        val secondToken = gate.nextToken()

        assertFalse(gate.emitIfCurrent(firstToken) { emittedTokens += firstToken })
        assertTrue(gate.emitIfCurrent(secondToken) { emittedTokens += secondToken })
        assertEquals(listOf(firstToken, secondToken), emittedTokens)
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
