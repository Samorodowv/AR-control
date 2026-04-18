package com.example.ar_control.recording

import android.view.Surface
import java.nio.ByteBuffer

enum class VideoFramePixelFormat {
    YUV420SP,
    NV21
}

fun interface VideoFrameConsumer {
    fun onFrame(frame: ByteBuffer, timestampNanos: Long)
}

sealed interface RecordingInputTarget {
    data class SurfaceTarget(val surface: Surface) : RecordingInputTarget

    data class FrameCallbackTarget(
        val pixelFormat: VideoFramePixelFormat,
        val frameConsumer: VideoFrameConsumer
    ) : RecordingInputTarget
}
