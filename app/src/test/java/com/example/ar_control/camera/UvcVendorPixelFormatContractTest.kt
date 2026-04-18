package com.example.ar_control.camera

import java.nio.file.Files
import java.nio.file.Path
import org.junit.Assert.assertTrue
import org.junit.Test

class UvcVendorPixelFormatContractTest {

    @Test
    fun callbackPixelFormatMapping_matchesFrameConversionSemantics() {
        val source = String(Files.readAllBytes(resolveVendorPreviewSource()))

        assertTrue(
            "PIXEL_FORMAT_YUV20SP should use uvc_yuyv2yuv420SP (UV order)",
            Regex(
                """case\s+PIXEL_FORMAT_YUV20SP\s*:\s*.*?mFrameCallbackFunc\s*=\s*uvc_yuyv2yuv420SP\s*;""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
            ).containsMatchIn(source)
        )

        assertTrue(
            "PIXEL_FORMAT_NV21 should use uvc_yuyv2iyuv420SP (VU order)",
            Regex(
                """case\s+PIXEL_FORMAT_NV21\s*:\s*.*?mFrameCallbackFunc\s*=\s*uvc_yuyv2iyuv420SP\s*;""",
                setOf(RegexOption.DOT_MATCHES_ALL, RegexOption.MULTILINE)
            ).containsMatchIn(source)
        )
    }

    private fun resolveVendorPreviewSource(): Path {
        val cwd = Path.of("").toAbsolutePath()
        val rootCandidate = cwd.resolve("vendor/uvccamera/src/main/jni/UVCCamera/UVCPreview.cpp")
        if (Files.exists(rootCandidate)) {
            return rootCandidate
        }

        val parentCandidate = cwd.resolve("../vendor/uvccamera/src/main/jni/UVCCamera/UVCPreview.cpp")
            .normalize()
        check(Files.exists(parentCandidate)) {
            "Could not locate vendored UVCPreview.cpp from $cwd"
        }
        return parentCandidate
    }
}
