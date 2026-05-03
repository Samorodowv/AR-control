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
        val minimumFrameIntervalNanos: Long = 0L,
        val frameConsumer: VideoFrameConsumer
    ) : RecordingInputTarget
}

object FrameCallbackTargetFanOut {
    fun combine(
        targets: List<RecordingInputTarget.FrameCallbackTarget>
    ): RecordingInputTarget.FrameCallbackTarget {
        require(targets.isNotEmpty()) { "Frame callback targets cannot be empty" }
        val pixelFormat = targets.first().pixelFormat
        require(targets.all { it.pixelFormat == pixelFormat }) {
            "All frame callback targets must use the same pixel format"
        }
        if (targets.size == 1) {
            return targets.single()
        }
        return RecordingInputTarget.FrameCallbackTarget(
            pixelFormat = pixelFormat,
            minimumFrameIntervalNanos = targets.minOf { target -> target.minimumFrameIntervalNanos },
            frameConsumer = VideoFrameConsumer { frame, timestampNanos ->
                for (target in targets) {
                    target.frameConsumer.onFrame(frame.asReadOnlyBuffer(), timestampNanos)
                }
            }
        )
    }
}
