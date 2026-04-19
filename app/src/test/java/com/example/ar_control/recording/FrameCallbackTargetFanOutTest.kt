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
                    frameConsumer = VideoFrameConsumer { frame, timestampNanos ->
                        firstFrames += frame.copyBytes()
                        firstTimestamps += timestampNanos
                    }
                ),
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    frameConsumer = VideoFrameConsumer { frame, timestampNanos ->
                        secondFrames += frame.copyBytes()
                        secondTimestamps += timestampNanos
                    }
                )
            )
        )

        combined.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(1, 2, 3, 4)), 42L)

        assertEquals(VideoFramePixelFormat.YUV420SP, combined.pixelFormat)
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

    private fun ByteBuffer.copyBytes(): ByteArray {
        val duplicate = duplicate()
        val bytes = ByteArray(duplicate.remaining())
        duplicate.get(bytes)
        return bytes
    }
}
