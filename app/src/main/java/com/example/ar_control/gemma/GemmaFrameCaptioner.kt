package com.example.ar_control.gemma

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFrameConsumer
import com.example.ar_control.recording.VideoFramePixelFormat

interface GemmaFrameCaptioner {
    fun start(
        modelPath: String,
        previewSize: PreviewSize,
        onCaptionUpdated: (String) -> Unit,
        onError: (String) -> Unit
    ): GemmaCaptionSession
}

interface GemmaCaptionSession : AutoCloseable {
    val inputTarget: RecordingInputTarget.FrameCallbackTarget

    override fun close()
}

object NoOpGemmaFrameCaptioner : GemmaFrameCaptioner {
    override fun start(
        modelPath: String,
        previewSize: PreviewSize,
        onCaptionUpdated: (String) -> Unit,
        onError: (String) -> Unit
    ): GemmaCaptionSession {
        return object : GemmaCaptionSession {
            override val inputTarget: RecordingInputTarget.FrameCallbackTarget =
                RecordingInputTarget.FrameCallbackTarget(
                    pixelFormat = VideoFramePixelFormat.YUV420SP,
                    frameConsumer = VideoFrameConsumer { _, _ -> }
                )

            override fun close() = Unit
        }
    }
}
