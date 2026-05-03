package com.example.ar_control.recording

import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FrameCallbackTargetFanOutTest {

    @Test
    fun combine_dispatchesFramesToAllConsumers() {
        val firstFrames = mutableListOf<ByteArray>()
        val firstTimestamps = mutableListOf<Long>()
        val secondFrames = mutableListOf<ByteArray>()
        val secondTimestamps = mutableListOf<Long>()
        val combined = FrameCallbackTargetFanOut.combine(
            listOf(
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    minimumFrameIntervalNanos = 3_000_000_000L,
                    frameConsumer = VideoFrameConsumer { frame, timestampNanos ->
                        firstFrames += frame.copyBytes()
                        firstTimestamps += timestampNanos
                    }
                ),
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    minimumFrameIntervalNanos = 1_000_000_000L,
                    frameConsumer = VideoFrameConsumer { frame, timestampNanos ->
                        secondFrames += frame.copyBytes()
                        secondTimestamps += timestampNanos
                    }
                )
            )
        )

        combined.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 42L)

        assertEquals(VideoFramePixelFormat.YUV420SP, combined.pixelFormat)
        assertEquals(1_000_000_000L, combined.minimumFrameIntervalNanos)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), firstFrames.single())
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), secondFrames.single())
        assertEquals(listOf(42L), firstTimestamps)
        assertEquals(listOf(42L), secondTimestamps)
    }

    @Test(expected = IllegalArgumentException::class)
    fun combine_rejectsMismatchedPixelFormats() {
        FrameCallbackTargetFanOut.combine(
            listOf(
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    frameConsumer = VideoFrameConsumer { _, _ -> }
                ),
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.NV21,
                    frameConsumer = VideoFrameConsumer { _, _ -> }
                )
            )
        )
    }

    @Test
    fun combine_usesEveryFrameWhenAnyTargetNeedsEveryFrame() {
        val combined = FrameCallbackTargetFanOut.combine(
            listOf(
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    minimumFrameIntervalNanos = 3_000_000_000L,
                    frameConsumer = VideoFrameConsumer { _, _ -> }
                ),
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    frameConsumer = VideoFrameConsumer { _, _ -> }
                )
            )
        )

        assertEquals(0L, combined.minimumFrameIntervalNanos)
    }

    @Test
    fun combine_respectsEachTargetsOwnMinimumInterval() {
        val slowTimestamps = mutableListOf<Long>()
        val fastTimestamps = mutableListOf<Long>()
        val combined = FrameCallbackTargetFanOut.combine(
            listOf(
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    minimumFrameIntervalNanos = 250_000_000L,
                    frameConsumer = VideoFrameConsumer { _, timestampNanos ->
                        slowTimestamps += timestampNanos
                    }
                ),
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    minimumFrameIntervalNanos = 0L,
                    frameConsumer = VideoFrameConsumer { _, timestampNanos ->
                        fastTimestamps += timestampNanos
                    }
                )
            )
        )

        listOf(0L, 100_000_000L, 200_000_000L, 250_000_000L, 260_000_000L, 500_000_000L)
            .forEach { timestampNanos ->
                combined.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(7)), timestampNanos)
            }

        assertEquals(
            listOf(0L, 250_000_000L, 500_000_000L),
            slowTimestamps
        )
        assertEquals(
            listOf(0L, 100_000_000L, 200_000_000L, 250_000_000L, 260_000_000L, 500_000_000L),
            fastTimestamps
        )
    }

    @Test
    fun combine_respectsSingleTargetsOwnMinimumInterval() {
        val timestamps = mutableListOf<Long>()
        val combined = FrameCallbackTargetFanOut.combine(
            listOf(
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    minimumFrameIntervalNanos = 250_000_000L,
                    frameConsumer = VideoFrameConsumer { _, timestampNanos ->
                        timestamps += timestampNanos
                    }
                )
            )
        )

        listOf(0L, 100_000_000L, 250_000_000L, 260_000_000L, 500_000_000L)
            .forEach { timestampNanos ->
                combined.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(7)), timestampNanos)
            }

        assertEquals(250_000_000L, combined.minimumFrameIntervalNanos)
        assertEquals(
            listOf(0L, 250_000_000L, 500_000_000L),
            timestamps
        )
    }

    @Test
    fun combine_forwardsEveryFrameForNegativeMinimumInterval() {
        val timestamps = mutableListOf<Long>()
        val combined = FrameCallbackTargetFanOut.combine(
            listOf(
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    minimumFrameIntervalNanos = -1L,
                    frameConsumer = VideoFrameConsumer { _, timestampNanos ->
                        timestamps += timestampNanos
                    }
                )
            )
        )

        listOf(0L, 100_000_000L, 50_000_000L, 100_000_000L)
            .forEach { timestampNanos ->
                combined.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(7)), timestampNanos)
            }

        assertEquals(-1L, combined.minimumFrameIntervalNanos)
        assertEquals(
            listOf(0L, 100_000_000L, 50_000_000L, 100_000_000L),
            timestamps
        )
    }

    @Test
    fun combine_doesNotForwardOutOfOrderFramesForPositiveMinimumInterval() {
        val timestamps = mutableListOf<Long>()
        val combined = FrameCallbackTargetFanOut.combine(
            listOf(
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    minimumFrameIntervalNanos = 100_000_000L,
                    frameConsumer = VideoFrameConsumer { _, timestampNanos ->
                        timestamps += timestampNanos
                    }
                )
            )
        )

        listOf(300_000_000L, 200_000_000L, 400_000_000L)
            .forEach { timestampNanos ->
                combined.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(7)), timestampNanos)
            }

        assertEquals(
            listOf(300_000_000L, 400_000_000L),
            timestamps
        )
    }

    private fun ByteBuffer.copyBytes(): ByteArray {
        val duplicate = duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }
}
