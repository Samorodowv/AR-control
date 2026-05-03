package com.example.ar_control.detection

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.performance.FramesPerSecondTracker
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFrameConsumer
import com.example.ar_control.recording.VideoFramePixelFormat
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean

class BackgroundObjectDetectionSession(
    private val previewSize: PreviewSize,
    private val processor: FrameDetectionProcessor,
    private val onDetectionsUpdated: (List<DetectedObject>) -> Unit,
    private val onSessionStatsUpdated: (DetectionSessionStats) -> Unit = {}
) : ObjectDetectionSession {

    private val isClosed = AtomicBoolean(false)
    private val frameLock = Object()
    private val inferenceFpsTracker = FramesPerSecondTracker()
    private val workerThread = Thread(::runLoop, WORKER_THREAD_NAME).apply {
        isDaemon = true
        start()
    }

    @Volatile
    private var latestPendingFrame: PendingFrame? = null

    override val inputTarget: RecordingInputTarget.FrameCallbackTarget =
        RecordingInputTarget.FrameCallbackTarget(
            pixelFormat = VideoFramePixelFormat.YUV420SP,
            frameConsumer = VideoFrameConsumer { frame, timestampNanos ->
                enqueueFrame(frame, timestampNanos)
            }
        )

    init {
        onSessionStatsUpdated(
            DetectionSessionStats(
                backendLabel = processor.runtimeBackendLabel,
                inferenceFps = 0f
            )
        )
    }

    override fun close() {
        if (!isClosed.compareAndSet(false, true)) {
            return
        }
        synchronized(frameLock) {
            latestPendingFrame = null
            frameLock.notifyAll()
        }
        workerThread.interrupt()
    }

    private fun enqueueFrame(frame: ByteBuffer, timestampNanos: Long) {
        if (isClosed.get()) {
            return
        }

        val copiedFrame = frame.asReadOnlyBuffer().let { buffer ->
            ByteArray(buffer.remaining()).also(buffer::get)
        }

        synchronized(frameLock) {
            if (isClosed.get()) {
                return
            }
            latestPendingFrame = PendingFrame(
                bytes = copiedFrame,
                timestampNanos = timestampNanos
            )
            frameLock.notifyAll()
        }
    }

    private fun runLoop() {
        try {
            while (true) {
                val frame = awaitNextFrame() ?: return
                val detections = processFrame(frame) ?: return
                val inferenceFps = inferenceFpsTracker.recordFrame(System.nanoTime()) ?: 0f
                if (!isClosed.get()) {
                    onSessionStatsUpdated(
                        DetectionSessionStats(
                            backendLabel = processor.runtimeBackendLabel,
                            inferenceFps = inferenceFps
                        )
                    )
                    onDetectionsUpdated(detections)
                }
            }
        } finally {
            processor.close()
        }
    }

    private fun awaitNextFrame(): PendingFrame? {
        try {
            synchronized(frameLock) {
                while (!isClosed.get() && latestPendingFrame == null) {
                    frameLock.wait()
                }
                if (isClosed.get()) {
                    return null
                }
                return latestPendingFrame.also {
                    latestPendingFrame = null
                }
            }
        } catch (_: InterruptedException) {
            return null
        }
    }

    private fun processFrame(frame: PendingFrame): List<DetectedObject>? {
        return try {
            processor.process(
                frameBytes = frame.bytes,
                previewSize = previewSize,
                timestampNanos = frame.timestampNanos
            )
        } catch (_: InterruptedException) {
            null
        }
    }

    private data class PendingFrame(
        val bytes: ByteArray,
        val timestampNanos: Long
    )

    private companion object {
        const val WORKER_THREAD_NAME = "object-detection"
    }
}
