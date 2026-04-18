package com.example.ar_control.camera

import com.serenegiant.usb.Size
import com.serenegiant.usb.UVCCamera
import org.junit.Assert.assertEquals
import org.junit.Test

class UvcPreviewConfiguratorTest {

    @Test
    fun configure_usesLargestSupportedSizeWithMjpegFirst() {
        val camera = FakePreviewConfigCamera(
            supportedSizes = listOf(
                Size(6, 0, 0, 640, 480),
                Size(6, 1, 1, 1920, 1080)
            )
        )

        val applied = UvcPreviewConfigurator.configure(camera)

        assertEquals(
            listOf(
                PreviewConfigAttempt(
                    width = 1920,
                    height = 1080,
                    frameFormat = UVCCamera.FRAME_FORMAT_MJPEG
                )
            ),
            camera.attempts
        )
        assertEquals(
            PreviewConfigAttempt(
                width = 1920,
                height = 1080,
                frameFormat = UVCCamera.FRAME_FORMAT_MJPEG
            ),
            applied
        )
    }

    @Test
    fun configure_fallsBackToYuyvWhenMjpegFails() {
        val camera = FakePreviewConfigCamera(
            supportedSizes = listOf(Size(6, 0, 0, 1920, 1080)),
            failingFormats = setOf(UVCCamera.FRAME_FORMAT_MJPEG)
        )

        val applied = UvcPreviewConfigurator.configure(camera)

        assertEquals(
            listOf(
                PreviewConfigAttempt(
                    width = 1920,
                    height = 1080,
                    frameFormat = UVCCamera.FRAME_FORMAT_MJPEG
                ),
                PreviewConfigAttempt(
                    width = 1920,
                    height = 1080,
                    frameFormat = UVCCamera.DEFAULT_PREVIEW_MODE
                )
            ),
            camera.attempts
        )
        assertEquals(
            PreviewConfigAttempt(
                width = 1920,
                height = 1080,
                frameFormat = UVCCamera.DEFAULT_PREVIEW_MODE
            ),
            applied
        )
    }
}

private class FakePreviewConfigCamera(
    private val supportedSizes: List<Size>,
    private val failingFormats: Set<Int> = emptySet()
) : PreviewConfigCamera {

    val attempts = mutableListOf<PreviewConfigAttempt>()

    override fun getSupportedSizes(): List<Size> = supportedSizes

    override fun setPreviewSize(
        width: Int,
        height: Int,
        minFps: Int,
        maxFps: Int,
        frameFormat: Int,
        bandwidthFactor: Float
    ) {
        val attempt = PreviewConfigAttempt(width, height, frameFormat)
        attempts += attempt
        if (frameFormat in failingFormats) {
            throw IllegalArgumentException("unsupported_format_$frameFormat")
        }
    }
}
