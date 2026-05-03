package com.example.ar_control.detection

import com.example.ar_control.camera.PreviewSize

interface FrameDetectionProcessor : AutoCloseable {
    val runtimeBackendLabel: String

    fun process(
        frameBytes: ByteArray,
        previewSize: PreviewSize,
        timestampNanos: Long
    ): List<DetectedObject>

    override fun close()
}
