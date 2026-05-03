package com.example.ar_control.face

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFrameConsumer
import com.example.ar_control.recording.VideoFramePixelFormat

interface FaceRecognizer {
    fun start(
        previewSize: PreviewSize,
        onStateUpdated: (FaceRecognitionState) -> Unit
    ): FaceRecognitionSession
}

interface FaceRecognitionSession : AutoCloseable {
    val inputTarget: RecordingInputTarget.FrameCallbackTarget

    val currentState: FaceRecognitionState

    fun rememberCurrentFace(): FaceEnrollmentResult

    override fun close()
}

class NoOpFaceRecognizer(
    private val embeddingStore: FaceEmbeddingStore = InMemoryFaceEmbeddingStore()
) : FaceRecognizer {
    override fun start(
        previewSize: PreviewSize,
        onStateUpdated: (FaceRecognitionState) -> Unit
    ): FaceRecognitionSession {
        val state = FaceRecognitionState(modelReady = false)
        onStateUpdated(state)
        return object : FaceRecognitionSession {
            private val enrollmentController = FaceEnrollmentController(embeddingStore)

            override val inputTarget: RecordingInputTarget.FrameCallbackTarget =
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    frameConsumer = VideoFrameConsumer { _, _ -> }
                )

            override val currentState: FaceRecognitionState = state

            override fun rememberCurrentFace(): FaceEnrollmentResult {
                return enrollmentController.rememberCurrentFace(currentState)
            }

            override fun close() = Unit
        }
    }
}
