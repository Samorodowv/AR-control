package com.example.ar_control.detection

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFrameConsumer
import com.example.ar_control.recording.VideoFramePixelFormat

interface ObjectDetector {
    fun start(
        previewSize: PreviewSize,
        onDetectionsUpdated: (List<DetectedObject>) -> Unit
    ): ObjectDetectionSession
}

interface ObjectDetectionSession : AutoCloseable {
    val inputTarget: RecordingInputTarget.FrameCallbackTarget

    override fun close()
}

object NoOpObjectDetector : ObjectDetector {
    override fun start(
        previewSize: PreviewSize,
        onDetectionsUpdated: (List<DetectedObject>) -> Unit
    ): ObjectDetectionSession {
        return object : ObjectDetectionSession {
            override val inputTarget: RecordingInputTarget.FrameCallbackTarget =
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    frameConsumer = VideoFrameConsumer { _, _ -> }
                )

            override fun close() = Unit
        }
    }
}
