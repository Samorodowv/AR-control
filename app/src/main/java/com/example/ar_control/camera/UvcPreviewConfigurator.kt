package com.example.ar_control.camera

import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera

internal interface PreviewConfigCamera {
    fun getSupportedSizes(): List<Size>

    fun setPreviewSize(
        width: Int,
        height: Int,
        minFps: Int,
        maxFps: Int,
        frameFormat: Int,
        bandwidthFactor: Float
    )
}

internal data class PreviewConfigAttempt(
    val width: Int,
    val height: Int,
    val frameFormat: Int
)

internal object UvcPreviewConfigurator {

    fun configure(camera: PreviewConfigCamera): PreviewConfigAttempt {
        val supportedSize = camera.getSupportedSizes()
            .sortedWith(compareByDescending<Size> { it.width * it.height }.thenByDescending { it.width })
            .firstOrNull()

        val preferredWidth = supportedSize?.width ?: UVCCamera.DEFAULT_PREVIEW_WIDTH
        val preferredHeight = supportedSize?.height ?: UVCCamera.DEFAULT_PREVIEW_HEIGHT

        val attempts = linkedSetOf(
            PreviewConfigAttempt(
                width = preferredWidth,
                height = preferredHeight,
                frameFormat = UVCCamera.FRAME_FORMAT_MJPEG
            ),
            PreviewConfigAttempt(
                width = preferredWidth,
                height = preferredHeight,
                frameFormat = UVCCamera.DEFAULT_PREVIEW_MODE
            ),
            PreviewConfigAttempt(
                width = UVCCamera.DEFAULT_PREVIEW_WIDTH,
                height = UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                frameFormat = UVCCamera.FRAME_FORMAT_MJPEG
            ),
            PreviewConfigAttempt(
                width = UVCCamera.DEFAULT_PREVIEW_WIDTH,
                height = UVCCamera.DEFAULT_PREVIEW_HEIGHT,
                frameFormat = UVCCamera.DEFAULT_PREVIEW_MODE
            )
        )

        var lastError: IllegalArgumentException? = null
        for (attempt in attempts) {
            try {
                camera.setPreviewSize(
                    width = attempt.width,
                    height = attempt.height,
                    minFps = UVCCamera.DEFAULT_PREVIEW_MIN_FPS,
                    maxFps = UVCCamera.DEFAULT_PREVIEW_MAX_FPS,
                    frameFormat = attempt.frameFormat,
                    bandwidthFactor = UVCCamera.DEFAULT_BANDWIDTH
                )
                return attempt
            } catch (error: IllegalArgumentException) {
                lastError = error
            }
        }

        throw lastError ?: IllegalStateException("Failed to configure UVC preview")
    }
}

internal class UvcPreviewConfigCamera(
    private val camera: UVCCamera
) : PreviewConfigCamera {

    override fun getSupportedSizes(): List<Size> = camera.supportedSizeList

    override fun setPreviewSize(
        width: Int,
        height: Int,
        minFps: Int,
        maxFps: Int,
        frameFormat: Int,
        bandwidthFactor: Float
    ) {
        camera.setPreviewSize(width, height, minFps, maxFps, frameFormat, bandwidthFactor)
    }
}
