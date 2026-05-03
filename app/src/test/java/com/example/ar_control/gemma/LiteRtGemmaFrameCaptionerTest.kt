package com.example.ar_control.gemma

import android.graphics.BitmapFactory
import com.example.ar_control.ArControlApp
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.recording.VideoFramePixelFormat
import com.google.ai.edge.litertlm.Backend
import java.nio.ByteBuffer
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
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
        assertEquals(3_000_000_000L, session.inputTarget.minimumFrameIntervalNanos)

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
    fun frameConsumerReturnsBeforeInferenceDispatcherRuns() = runTest {
        val model = FakeGemmaCaptionModel("a desk")
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { model },
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L }
        )
        val session = captioner.start(
            modelPath = "model.task",
            previewSize = PreviewSize(width = 2, height = 2),
            onCaptionUpdated = {},
            onError = {}
        )

        session.inputTarget.frameConsumer.onFrame(testYuv420SpFrame(), 1L)

        assertEquals(0, model.captionCalls)
        advanceUntilIdle()
        assertEquals(1, model.captionCalls)
    }

    @Test
    fun sessionUsesPromptProviderForCaptionRequest() = runTest {
        val model = FakeGemmaCaptionModel("a desk")
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { model },
            dispatcher = StandardTestDispatcher(testScheduler),
            captionPromptProvider = { "Опиши только движение." },
            clock = { 1_000L }
        )
        val session = captioner.start(
            modelPath = "model.task",
            previewSize = PreviewSize(width = 2, height = 2),
            onCaptionUpdated = {},
            onError = {}
        )

        session.inputTarget.frameConsumer.onFrame(testYuv420SpFrame(), 1L)
        advanceUntilIdle()

        assertEquals("Опиши только движение.", model.lastPrompt)
    }

    @Test
    fun sessionStreamsPartialCaptionsBeforeFinalCaption() = runTest {
        val model = FakeGemmaCaptionModel(
            caption = "Черный экран без объектов.",
            partialCaptions = listOf(
                "Чер",
                "Черный экран",
                "Черный экран без объектов."
            )
        )
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { model },
            dispatcher = StandardTestDispatcher(testScheduler),
            minPartialCaptionUpdateIntervalMillis = 0L,
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
        advanceUntilIdle()

        assertEquals(
            listOf(
                "чер",
                "черный экран",
                "черный экран без объектов"
            ),
            captions
        )
    }

    @Test
    fun sessionThrottlesRapidPartialCaptionsButPublishesFinalCaption() = runTest {
        val model = FakeGemmaCaptionModel(
            caption = "Черный экран без объектов.",
            partialCaptions = listOf(
                "Чер",
                "Черный экран",
                "Черный экран без"
            )
        )
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
        advanceUntilIdle()

        assertEquals(
            listOf(
                "чер",
                "черный экран без объектов"
            ),
            captions
        )
    }

    @Test
    fun sanitizeCaptionKeepsDetailedTextAcrossLines() {
        assertEquals(
            "a person at a desk. Extra detail",
            sanitizeCaption("A person at a desk.\nExtra detail.")
        )
        assertEquals("", sanitizeCaption("   "))
    }

    @Test
    fun sanitizeCaptionCollapsesSpacingAroundWordsAndHyphens() {
        assertEquals(
            "close-up of a camera setup",
            sanitizeCaption("close - up  of  a  camera  setup")
        )
    }

    @Test
    fun sanitizeCaptionPreservesLongDetailedRussianCaption() {
        val caption =
            "На столе лежит открытый ноутбук, рядом стоит белая кружка, " +
                "за экраном видна полка с книгами и мягкий дневной свет из окна."

        assertEquals(
            "на столе лежит открытый ноутбук, рядом стоит белая кружка, " +
                "за экраном видна полка с книгами и мягкий дневной свет из окна",
            sanitizeCaption(caption)
        )
    }

    @Test
    fun sanitizeCaptionDoesNotCutHundredWordPromptResponse() {
        val caption = (1..100).joinToString(separator = " ") { index -> "слово$index" }

        assertEquals(caption, sanitizeCaption(caption))
    }

    @Test
    fun sanitizeCaptionJoinsCommonRussianSuffixFragments() {
        assertEquals(
            "темный стол и красная коробка",
            sanitizeCaption("тем ный стол и крас ная короб ка")
        )
    }

    @Test
    fun combineGemmaTextChunksPreservesRawTokenSpacingWithoutInjectingSpaces() {
        assertEquals(
            "на столе несколько видимых кадров",
            combineGemmaTextChunks(
                listOf("на столе несколько види", "мых ка", "дров")
            )
        )
    }

    @Test
    fun captionPromptRequestsDetailedRussianOnlyOutputUpToSixtyWords() {
        assertEquals(
            "Опиши подробно на русском языке. " +
                "Ответь только по-русски, без английских слов. " +
                "2-3 предложения, до 60 слов. Не упоминай, что это изображение.",
            CAPTION_PROMPT
        )
    }

    @Test
    fun createEngineConfigUsesGalleryCompatibleGpuBackendsAndDisablesAudioForGemmaModel() {
        val config = createGemmaEngineConfig(
            modelPath = "/models/gemma.litertlm"
        )

        assertEquals("/models/gemma.litertlm", config.modelPath)
        assertNull(config.cacheDir)
        assertEquals(1024, config.maxNumTokens)
        assertTrue(config.backend is Backend.GPU)
        assertTrue(config.visionBackend is Backend.GPU)
        assertNull(config.audioBackend)
    }

    @Test
    fun sessionSendsResizedPngPayloadToGemma() = runTest {
        val model = FakeGemmaCaptionModel("a desk")
        val previewSize = PreviewSize(width = 1536, height = 768)
        val captioner = LiteRtGemmaFrameCaptioner(
            modelFactory = { model },
            dispatcher = StandardTestDispatcher(testScheduler),
            clock = { 1_000L }
        )
        val session = captioner.start(
            modelPath = "model.task",
            previewSize = previewSize,
            onCaptionUpdated = {},
            onError = {}
        )

        session.inputTarget.frameConsumer.onFrame(solidYuv420SpFrame(previewSize), 1L)
        advanceUntilIdle()

        val imageBytes = requireNotNull(model.lastImageBytes)
        assertTrue(imageBytes.startsWithPngSignature())
        val bitmap = BitmapFactory.decodeByteArray(imageBytes, 0, imageBytes.size)
        assertEquals(512, bitmap.width)
        assertEquals(256, bitmap.height)
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

    private fun solidYuv420SpFrame(previewSize: PreviewSize): ByteBuffer {
        return ByteBuffer.wrap(
            ByteArray(previewSize.width * previewSize.height * 3 / 2) {
                0x80.toByte()
            }
        )
    }

    private fun ByteArray.startsWithPngSignature(): Boolean {
        return size >= PNG_SIGNATURE.size &&
            PNG_SIGNATURE.indices.all { index -> this[index] == PNG_SIGNATURE[index] }
    }

    private class FakeGemmaCaptionModel(
        private val caption: String = "",
        private val captionFailure: Exception? = null,
        private val partialCaptions: List<String> = emptyList(),
        private val beforeCaptionReturns: suspend () -> Unit = {}
    ) : GemmaCaptionModel {
        var captionCalls = 0
            private set
        var closeCalls = 0
            private set
        var lastImageBytes: ByteArray? = null
            private set
        var lastPrompt: String? = null
            private set

        override suspend fun caption(
            imageBytes: ByteArray,
            prompt: String,
            onPartialCaption: (String) -> Unit
        ): String {
            captionCalls += 1
            lastImageBytes = imageBytes
            lastPrompt = prompt
            beforeCaptionReturns()
            captionFailure?.let { throw it }
            partialCaptions.forEach(onPartialCaption)
            return caption
        }

        override fun close() {
            closeCalls += 1
        }
    }

    private companion object {
        val PNG_SIGNATURE = byteArrayOf(
            0x89.toByte(),
            0x50,
            0x4E,
            0x47,
            0x0D,
            0x0A,
            0x1A,
            0x0A
        )
    }
}
