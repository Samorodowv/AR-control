package com.example.ar_control.detection

import com.example.ar_control.camera.PreviewSize
import java.nio.ByteBuffer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BackgroundObjectDetectionSessionTest {

    @Test
    fun inputTarget_processesFramesAndPublishesDetections() {
        val expectedDetections = listOf(
            DetectedObject(
                labelIndex = 0,
                label = "person",
                confidence = 0.93f,
                boundingBox = DetectionBoundingBox(1f, 2f, 3f, 4f)
            )
        )
        val processor = FakeFrameDetectionProcessor(expectedDetections)
        val publishedDetections = mutableListOf<List<DetectedObject>>()
        val latch = CountDownLatch(1)
        val session = BackgroundObjectDetectionSession(
            previewSize = PreviewSize(width = 640, height = 480),
            processor = processor
        ) { detections ->
            publishedDetections += detections
            latch.countDown()
        }

        try {
            assertEquals(com.example.ar_control.recording.VideoFramePixelFormat.YUV420SP, session.inputTarget.pixelFormat)

            session.inputTarget.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(9, 8, 7, 6)), 55L)

            assertTrue(latch.await(2, TimeUnit.SECONDS))
            assertEquals(expectedDetections, publishedDetections.single())
            assertArrayEquals(byteArrayOf(9, 8, 7, 6), processor.frames.single())
            assertEquals(listOf(55L), processor.timestamps)
        } finally {
            session.close()
        }
    }

    @Test
    fun close_stopsPublishingFurtherResults() {
        val processor = FakeFrameDetectionProcessor(emptyList())
        val publishedDetections = mutableListOf<List<DetectedObject>>()
        val session = BackgroundObjectDetectionSession(
            previewSize = PreviewSize(width = 640, height = 480),
            processor = processor
        ) { detections ->
            publishedDetections += detections
        }

        session.close()
        session.inputTarget.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(1, 2, 3)), 77L)
        Thread.sleep(100)

        assertTrue(publishedDetections.isEmpty())
    }

    @Test
    fun inputTarget_keepsOnlyLatestPendingFrameWhileProcessorIsBusy() {
        val processor = BlockingFrameDetectionProcessor()
        val session = BackgroundObjectDetectionSession(
            previewSize = PreviewSize(width = 640, height = 480),
            processor = processor
        ) { }

        try {
            session.inputTarget.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(1)), 10L)
            assertTrue(processor.firstFrameStarted.await(2, TimeUnit.SECONDS))

            session.inputTarget.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(2)), 20L)
            session.inputTarget.frameConsumer.onFrame(ByteBuffer.wrap(byteArrayOf(3)), 30L)

            processor.releaseFirstFrame.countDown()
            assertTrue(processor.secondFrameStarted.await(2, TimeUnit.SECONDS))

            assertEquals(listOf(10L, 30L), processor.timestamps)
            assertEquals(
                listOf(byteArrayOf(1), byteArrayOf(3)).map { it.toList() },
                processor.frames.map { it.toList() }
            )
        } finally {
            session.close()
        }
    }
}

private class FakeFrameDetectionProcessor(
    private val detections: List<DetectedObject>
) : FrameDetectionProcessor {
    val frames = mutableListOf<ByteArray>()
    val timestamps = mutableListOf<Long>()

    override fun process(
        frameBytes: ByteArray,
        previewSize: PreviewSize,
        timestampNanos: Long
    ): List<DetectedObject> {
        frames += frameBytes
        timestamps += timestampNanos
        return detections
    }

    override fun close() = Unit
}

private class BlockingFrameDetectionProcessor : FrameDetectionProcessor {
    val frames = CopyOnWriteArrayList<ByteArray>()
    val timestamps = CopyOnWriteArrayList<Long>()
    val firstFrameStarted = CountDownLatch(1)
    val secondFrameStarted = CountDownLatch(1)
    val releaseFirstFrame = CountDownLatch(1)
    private val processCalls = AtomicInteger(0)

    override fun process(
        frameBytes: ByteArray,
        previewSize: PreviewSize,
        timestampNanos: Long
    ): List<DetectedObject> {
        val callIndex = processCalls.incrementAndGet()
        frames += frameBytes
        timestamps += timestampNanos
        when (callIndex) {
            1 -> {
                firstFrameStarted.countDown()
                releaseFirstFrame.await(2, TimeUnit.SECONDS)
            }

            2 -> secondFrameStarted.countDown()
        }
        return emptyList()
    }

    override fun close() = Unit
}
