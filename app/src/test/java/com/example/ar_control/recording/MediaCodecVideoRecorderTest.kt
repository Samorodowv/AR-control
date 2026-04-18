package com.example.ar_control.recording

import android.graphics.SurfaceTexture
import android.view.Surface
import com.example.ar_control.ArControlApp
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.diagnostics.InMemorySessionLog
import java.io.Closeable
import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class MediaCodecVideoRecorderTest {

    @Test
    fun defaultFrameCallbackPixelFormat_usesYuv420sp() {
        assertEquals(
            VideoFramePixelFormat.YUV420SP,
            DEFAULT_RECORDER_FRAME_CALLBACK_PIXEL_FORMAT
        )
    }

    @Test
    fun yuv420spFramesPassThroughForSemiPlanarEncoding() {
        val converter = Yuv420FrameConverter.forColorFormat(
            width = 2,
            height = 2,
            sourcePixelFormat = VideoFramePixelFormat.YUV420SP,
            colorFormat = android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        )
        val source = byteArrayOf(1, 2, 3, 4, 100.toByte(), 50.toByte())
        val destination = ByteArray(source.size)

        val written = converter.convert(source, destination)

        assertEquals(source.size, written)
        assertArrayEquals(source, destination)
    }

    @Test
    fun nv21FramesSwapChromaForSemiPlanarEncoding() {
        val converter = Yuv420FrameConverter.forColorFormat(
            width = 2,
            height = 2,
            sourcePixelFormat = VideoFramePixelFormat.NV21,
            colorFormat = android.media.MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
        )
        val source = byteArrayOf(1, 2, 3, 4, 50.toByte(), 100.toByte())
        val destination = ByteArray(source.size)

        val written = converter.convert(source, destination)

        assertEquals(source.size, written)
        assertArrayEquals(byteArrayOf(1, 2, 3, 4, 100.toByte(), 50.toByte()), destination)
    }

    @Test
    fun startReturnsInputTargetAndOutputFile() = runTest {
        val testSurface = TestSurface.create()
        try {
            val factory = FakeRecorderEngineFactory(testSurface = testSurface)
            val outputDirectory = Files.createTempDirectory("video_recorder_start").toFile()
            val recorder = MediaCodecVideoRecorder(
                outputDirectory = outputDirectory,
                sessionLog = InMemorySessionLog(),
                clock = { 1_700_000_000_000L },
                fileNameFactory = { "clip-start.mp4" },
                engineFactory = factory
            )

            val result = recorder.start(PreviewSize(width = 1920, height = 1080))

            assertTrue(result is VideoRecorder.StartResult.Started)
            val started = result as VideoRecorder.StartResult.Started
            val inputTarget = started.inputTarget as RecordingInputTarget.SurfaceTarget
            assertSame(testSurface.surface, inputTarget.surface)
            assertEquals(File(outputDirectory, "clip-start.mp4").absolutePath, started.outputFilePath)
            assertEquals(1_700_000_000_000L, started.startedAtEpochMillis)
            assertEquals(1, factory.createdEngines.size)
        } finally {
            testSurface.close()
        }
    }

    @Test
    fun stopReturnsFinishedClipMetadata() = runTest {
        val testSurface = TestSurface.create()
        try {
            val outputDirectory = Files.createTempDirectory("video_recorder_stop").toFile()
            val clock = MutableClock(1_700_000_000_000L)
            val recorder = MediaCodecVideoRecorder(
                outputDirectory = outputDirectory,
                sessionLog = InMemorySessionLog(),
                clock = clock::now,
                fileNameFactory = { "clip-stop.mp4" },
                engineFactory = FakeRecorderEngineFactory(testSurface = testSurface)
            )

            recorder.start(PreviewSize(width = 1280, height = 720))
            clock.time = 1_700_000_003_333L

            val result = recorder.stop()

            assertTrue(result is VideoRecorder.StopResult.Finished)
            val finished = result as VideoRecorder.StopResult.Finished
            assertEquals("clip-stop", finished.clip.id)
            assertEquals(File(outputDirectory, "clip-stop.mp4").absolutePath, finished.clip.filePath)
            assertEquals(1_700_000_000_000L, finished.clip.createdAtEpochMillis)
            assertEquals(3_333L, finished.clip.durationMillis)
            assertEquals(1280, finished.clip.width)
            assertEquals(720, finished.clip.height)
            assertEquals("video/mp4", finished.clip.mimeType)
            assertTrue(finished.clip.fileSizeBytes > 0)
        } finally {
            testSurface.close()
        }
    }

    @Test
    fun cancelReturnsCancelledAndDeletesPreparedOutputFile() = runTest {
        val testSurface = TestSurface.create()
        try {
            val outputDirectory = Files.createTempDirectory("video_recorder_cancel").toFile()
            val factory = FakeRecorderEngineFactory(testSurface = testSurface)
            val recorder = MediaCodecVideoRecorder(
                outputDirectory = outputDirectory,
                sessionLog = InMemorySessionLog(),
                clock = { 1_700_000_000_000L },
                fileNameFactory = { "clip-cancel.mp4" },
                engineFactory = factory
            )

            recorder.start(PreviewSize(width = 640, height = 480))
            val outputFile = File(outputDirectory, "clip-cancel.mp4")
            assertTrue(outputFile.exists())

            val result = recorder.cancel()

            assertEquals(VideoRecorder.CancelResult.Cancelled, result)
            assertFalse(outputFile.exists())
            assertTrue(factory.createdEngines.single().cancelCalled)
        } finally {
            testSurface.close()
        }
    }

    @Test
    fun cancelReturnsFailureWhenCleanupFailsButDropsSession() = runTest {
        val testSurface = TestSurface.create()
        try {
            val outputDirectory = Files.createTempDirectory("video_recorder_cancel_fail").toFile()
            val factory = FakeRecorderEngineFactory(
                testSurface = testSurface,
                cancelFailure = IllegalStateException("cancel failed")
            )
            val recorder = MediaCodecVideoRecorder(
                outputDirectory = outputDirectory,
                sessionLog = InMemorySessionLog(),
                clock = { 1_700_000_000_000L },
                fileNameFactory = { "clip-cancel-fail.mp4" },
                engineFactory = factory
            )

            recorder.start(PreviewSize(width = 640, height = 480))

            val result = recorder.cancel()

            assertEquals(
                VideoRecorder.CancelResult.Failed("cancel failed"),
                result
            )
            assertTrue(factory.createdEngines.single().cancelCalled)
            assertEquals(
                VideoRecorder.CancelResult.Failed("recording_not_started"),
                recorder.cancel()
            )
        } finally {
            testSurface.close()
        }
    }

    @Test
    fun stopFailureCleansUpOutputFile() = runTest {
        val testSurface = TestSurface.create()
        try {
            val outputDirectory = Files.createTempDirectory("video_recorder_failure").toFile()
            val recorder = MediaCodecVideoRecorder(
                outputDirectory = outputDirectory,
                sessionLog = InMemorySessionLog(),
                clock = { 1_700_000_000_000L },
                fileNameFactory = { "clip-failure.mp4" },
                engineFactory = FakeRecorderEngineFactory(
                    testSurface = testSurface,
                    finalizeFailure = IllegalStateException("finalize failed")
                )
            )

            recorder.start(PreviewSize(width = 640, height = 480))
            val outputFile = File(outputDirectory, "clip-failure.mp4")

            val result = recorder.stop()

            assertTrue(result is VideoRecorder.StopResult.Failed)
            assertFalse(outputFile.exists())
        } finally {
            testSurface.close()
        }
    }

    @Test
    fun stopPreservesMuxedFileWhenEngineReportsNonFatalFinalizeWarning() = runTest {
        val testSurface = TestSurface.create()
        try {
            val outputDirectory = Files.createTempDirectory("video_recorder_warning").toFile()
            val recorder = MediaCodecVideoRecorder(
                outputDirectory = outputDirectory,
                sessionLog = InMemorySessionLog(),
                clock = { 1_700_000_000_500L },
                fileNameFactory = { "clip-warning.mp4" },
                engineFactory = FakeRecorderEngineFactory(
                    testSurface = testSurface,
                    finalizeResult = MediaCodecVideoRecorder.FinalizeResult.FinalizedWithWarning(
                        "surface release failed"
                    )
                )
            )

            recorder.start(PreviewSize(width = 800, height = 600))
            val outputFile = File(outputDirectory, "clip-warning.mp4")

            val result = recorder.stop()

            assertTrue(result is VideoRecorder.StopResult.Finished)
            assertTrue(outputFile.exists())
            assertTrue(outputFile.length() > 0L)
        } finally {
            testSurface.close()
        }
    }

    private class MutableClock(var time: Long) {
        fun now(): Long = time
    }

    private class TestSurface private constructor(
        val texture: SurfaceTexture,
        val surface: Surface
    ) : Closeable {

        private var closed = false

        override fun close() {
            if (!closed) {
                closed = true
                surface.release()
                texture.release()
            }
        }

        companion object {
            fun create(): TestSurface {
                val texture = SurfaceTexture(0)
                return TestSurface(texture, Surface(texture))
            }
        }
    }

    private class FakeRecorderEngineFactory(
        private val testSurface: TestSurface,
        private val finalizeFailure: Throwable? = null,
        private val cancelFailure: Throwable? = null,
        private val finalizeResult: MediaCodecVideoRecorder.FinalizeResult =
            MediaCodecVideoRecorder.FinalizeResult.Finalized
    ) : MediaCodecVideoRecorder.RecorderEngineFactory {

        val createdEngines = mutableListOf<FakeRecorderEngine>()

        override fun create(
            outputFile: File,
            previewSize: PreviewSize,
            sessionLog: com.example.ar_control.diagnostics.SessionLog
        ): MediaCodecVideoRecorder.RecorderEngine {
            return FakeRecorderEngine(
                outputFile = outputFile,
                inputTarget = RecordingInputTarget.SurfaceTarget(testSurface.surface),
                finalizeFailure = finalizeFailure,
                cancelFailure = cancelFailure,
                finalizeResult = finalizeResult
            ).also(createdEngines::add)
        }
    }

    private class FakeRecorderEngine(
        private val outputFile: File,
        override val inputTarget: RecordingInputTarget,
        private val finalizeFailure: Throwable?,
        private val cancelFailure: Throwable?,
        private val finalizeResult: MediaCodecVideoRecorder.FinalizeResult
    ) : MediaCodecVideoRecorder.RecorderEngine {

        var cancelCalled = false
            private set

        override fun finalizeRecording(): MediaCodecVideoRecorder.FinalizeResult {
            finalizeFailure?.let { throw it }
            outputFile.writeBytes(byteArrayOf(1, 2, 3, 4))
            return finalizeResult
        }

        override fun cancel() {
            cancelCalled = true
            cancelFailure?.let { throw it }
        }
    }
}
