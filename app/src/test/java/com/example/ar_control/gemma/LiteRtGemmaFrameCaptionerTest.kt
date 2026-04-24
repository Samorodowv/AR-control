package com.example.ar_control.gemma

import com.example.ar_control.ArControlApp
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.recording.VideoFramePixelFormat
import java.nio.ByteBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@OptIn(ExperimentalCoroutinesApi::class)
@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class LiteRtGemmaFrameCaptionerTest {

    @Test
    fun sessionDropsFramesInsideSampleInterval() = runTest {
        val model = FakeGemmaCaptionModel("a desk with a monitor")
        var now = 1_000L
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { model },
            dispatcher = StandardTestDispatcher(testScheduler),
            minCaptionIntervalMillis = 3_000L,
            clock = { now }
        )
        val captions = mutableListOf<String>()
        val session = captioner.start(
            modelPath = "model.task",
            previewSize = PreviewSize(width = 2, height = 2),
            onCaptionUpdated = captions::add,
            onError = {}
        )

        assertEquals(VideoFramePixelFormat.YUV420SP, session.inputTarget.pixelFormat)

        session.inputTarget.frameConsumer.onFrame(testYuv420SpFrame(), 1L)
        now += 1_000L
        session.inputTarget.frameConsumer.onFrame(testYuv420SpFrame(), 2L)
        advanceUntilIdle()

        assertEquals(listOf("a desk with a monitor"), captions)
        assertEquals(1, model.captionCalls)
    }

    @Test
    fun sessionReportsModelFailure() = runTest {
        val model = FakeGemmaCaptionModel(captionFailure = IllegalStateException("model_failed"))
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { model },
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L }
        )
        val errors = mutableListOf<String>()
        val session = captioner.start(
            modelPath = "model.task",
            previewSize = PreviewSize(width = 2, height = 2),
            onCaptionUpdated = {},
            onError = errors::add
        )

        session.inputTarget.frameConsumer.onFrame(testYuv420SpFrame(), 1L)
        advanceUntilIdle()

        assertEquals(listOf("model_failed"), errors)
    }

    @Test
    fun sanitizeCaptionKeepsSingleShortLine() {
        assertEquals(
            "a person at a desk",
            sanitizeCaption("A person at a desk.\nExtra detail.")
        )
        assertEquals("", sanitizeCaption("   "))
    }

    @Test
    fun closePreventsLaterCallbacksAndClosesModelOnce() = runTest {
        val model = FakeGemmaCaptionModel("A Desk.")
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { model },
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L }
        )
        val captions = mutableListOf<String>()
        val session = captioner.start(
            modelPath = "model.task",
            previewSize = PreviewSize(width = 2, height = 2),
            onCaptionUpdated = captions::add,
            onError = {}
        )

        session.inputTarget.frameConsumer.onFrame(testYuv420SpFrame(), 1L)
        session.close()
        session.close()
        advanceUntilIdle()

        assertEquals(emptyList<String>(), captions)
        assertEquals(1, model.closeCalls)
    }

    @Test
    fun closeDefersModelCloseUntilInFlightCaptionExits() = runTest {
        val captionStarted = CompletableDeferred<Unit>()
        val allowCaptionToFinish = CompletableDeferred<Unit>()
        val model = FakeGemmaCaptionModel(
            caption = "A Desk.",
            beforeCaptionReturns = {
                captionStarted.complete(Unit)
                allowCaptionToFinish.await()
            }
        )
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { model },
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L }
        )
        val captions = mutableListOf<String>()
        val errors = mutableListOf<String>()
        val session = captioner.start(
            modelPath = "model.task",
            previewSize = PreviewSize(width = 2, height = 2),
            onCaptionUpdated = captions::add,
            onError = errors::add
        )

        session.inputTarget.frameConsumer.onFrame(testYuv420SpFrame(), 1L)
        advanceUntilIdle()
        captionStarted.await()

        session.close()
        advanceUntilIdle()

        assertEquals(0, model.closeCalls)

        allowCaptionToFinish.complete(Unit)
        advanceUntilIdle()

        assertEquals(1, model.closeCalls)
        assertEquals(emptyList<String>(), captions)
        assertEquals(emptyList<String>(), errors)
    }

    @Test
    fun closeDuringInlineJobStartupDefersModelCloseUntilCaptionExits() = runTest {
        lateinit var session: GemmaCaptionSession
        lateinit var model: FakeGemmaCaptionModel
        var closeCallsObservedInsideCaption = -1
        model = FakeGemmaCaptionModel(
            caption = "A Desk.",
            beforeCaptionReturns = {
                session.close()
                closeCallsObservedInsideCaption = model.closeCalls
            }
        )
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { model },
            dispatcher = UnconfinedTestDispatcher(testScheduler),
            clock = { 1_000L }
        )
        val captions = mutableListOf<String>()
        val errors = mutableListOf<String>()
        session = captioner.start(
            modelPath = "model.task",
            previewSize = PreviewSize(width = 2, height = 2),
            onCaptionUpdated = captions::add,
            onError = errors::add
        )

        session.inputTarget.frameConsumer.onFrame(testYuv420SpFrame(), 1L)
        advanceUntilIdle()

        assertEquals(0, closeCallsObservedInsideCaption)
        assertEquals(1, model.closeCalls)
        assertEquals(emptyList<String>(), captions)
        assertEquals(emptyList<String>(), errors)
    }

    private fun testYuv420SpFrame(): ByteBuffer {
        return ByteBuffer.wrap(
            byteArrayOf(
                82, 82, 82, 82,
                90, 240.toByte()
            )
        )
    }

    private class FakeGemmaCaptionModel(
        private val caption: String = "",
        private val captionFailure: Exception? = null,
        private val beforeCaptionReturns: suspend () -> Unit = {}
    ) : GemmaCaptionModel {
        var captionCalls = 0
            private set
        var closeCalls = 0
            private set

        override suspend fun caption(jpegBytes: ByteArray): String {
            captionCalls += 1
            beforeCaptionReturns()
            captionFailure?.let { throw it }
            return caption
        }

        override fun close() {
            closeCalls += 1
        }
    }
}
