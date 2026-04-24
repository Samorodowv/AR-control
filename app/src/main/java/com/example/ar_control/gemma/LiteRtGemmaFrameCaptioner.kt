package com.example.ar_control.gemma

import android.content.Context
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFrameConsumer
import com.example.ar_control.recording.VideoFramePixelFormat
import com.google.ai.edge.litertlm.Backend
import com.google.ai.edge.litertlm.Content
import com.google.ai.edge.litertlm.Contents
import com.google.ai.edge.litertlm.Conversation
import com.google.ai.edge.litertlm.ConversationConfig
import com.google.ai.edge.litertlm.Engine
import com.google.ai.edge.litertlm.EngineConfig
import com.google.ai.edge.litertlm.SamplerConfig
import java.nio.ByteBuffer
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch

internal interface GemmaCaptionModel : AutoCloseable {
    suspend fun caption(jpegBytes: ByteArray): String

    override fun close()
}

class LiteRtGemmaFrameCaptioner internal constructor(
    private val modelFactory: (String) -> GemmaCaptionModel,
    private val dispatcher: CoroutineDispatcher,
    private val minCaptionIntervalMillis: Long = DEFAULT_MIN_CAPTION_INTERVAL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionLog: SessionLog = NoOpSessionLog
) : GemmaFrameCaptioner {

    constructor(
        context: Context,
        sessionLog: SessionLog = NoOpSessionLog
    ) : this(
        modelFactory = { modelPath ->
            LiteRtGemmaCaptionModel(
                modelPath = modelPath,
                cacheDir = context.applicationContext.cacheDir.absolutePath
            )
        },
        dispatcher = Dispatchers.Default,
        sessionLog = sessionLog
    )

    override fun start(
        modelPath: String,
        previewSize: PreviewSize,
        onCaptionUpdated: (String) -> Unit,
        onError: (String) -> Unit
    ): GemmaCaptionSession {
        return LiteRtGemmaCaptionSession(
            model = modelFactory(modelPath),
            previewSize = previewSize,
            dispatcher = dispatcher,
            minCaptionIntervalMillis = minCaptionIntervalMillis,
            clock = clock,
            sessionLog = sessionLog,
            onCaptionUpdated = onCaptionUpdated,
            onError = onError
        )
    }

    private class LiteRtGemmaCaptionSession(
        private val model: GemmaCaptionModel,
        private val previewSize: PreviewSize,
        dispatcher: CoroutineDispatcher,
        private val minCaptionIntervalMillis: Long,
        private val clock: () -> Long,
        private val sessionLog: SessionLog,
        private val onCaptionUpdated: (String) -> Unit,
        private val onError: (String) -> Unit
    ) : GemmaCaptionSession {
        private val scope = CoroutineScope(SupervisorJob() + dispatcher)
        private val closed = AtomicBoolean(false)
        private val inFlight = AtomicBoolean(false)
        private val closeLock = Any()
        private var activeInferenceJob: Job? = null
        private var modelClosed = false
        private var closeRequested = false
        private var lastAcceptedAtMillis: Long? = null

        override val inputTarget: RecordingInputTarget.FrameCallbackTarget =
            RecordingInputTarget.FrameCallbackTarget(
                pixelFormat = VideoFramePixelFormat.YUV420SP,
                frameConsumer = VideoFrameConsumer { frame, _ -> consumeFrame(frame) }
            )

        private fun consumeFrame(frame: ByteBuffer) {
            if (closed.get()) {
                return
            }
            val now = clock()
            val previousAcceptedAt = lastAcceptedAtMillis
            if (
                previousAcceptedAt != null &&
                now - previousAcceptedAt < minCaptionIntervalMillis
            ) {
                return
            }
            if (!inFlight.compareAndSet(false, true)) {
                return
            }
            lastAcceptedAtMillis = now
            val frameBytes = frame.copyRemainingBytes()
            val inferenceJob = scope.launch(start = CoroutineStart.LAZY) {
                try {
                    if (closed.get()) {
                        return@launch
                    }
                    val jpegBytes = Yuv420SpImageEncoder.encodeJpeg(frameBytes, previewSize)
                    if (closed.get()) {
                        return@launch
                    }
                    val caption = sanitizeCaption(model.caption(jpegBytes))
                    if (!closed.get() && caption.isNotBlank()) {
                        onCaptionUpdated(caption)
                    }
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    val reason = exception.message ?: exception::class.java.simpleName
                    sessionLog.record(SOURCE, "Caption failed: $reason")
                    if (!closed.get()) {
                        onError(reason)
                    }
                } finally {
                    inFlight.set(false)
                    val shouldCloseModel = synchronized(closeLock) {
                        activeInferenceJob = null
                        closeRequested && !modelClosed
                    }
                    if (shouldCloseModel) {
                        closeModelOnce()
                    }
                }
            }
            val shouldStart = synchronized(closeLock) {
                if (closeRequested || closed.get()) {
                    inFlight.set(false)
                    false
                } else {
                    activeInferenceJob = inferenceJob
                    true
                }
            }
            if (shouldStart) {
                inferenceJob.start()
            }
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) {
                return
            }
            val jobToWaitFor = synchronized(closeLock) {
                closeRequested = true
                activeInferenceJob?.takeUnless { inferenceJob -> inferenceJob.isCompleted }
            }
            if (jobToWaitFor == null) {
                closeModelOnce()
            } else {
                jobToWaitFor.invokeOnCompletion { closeModelOnce() }
            }
        }

        private fun closeModelOnce() {
            val shouldClose = synchronized(closeLock) {
                if (modelClosed) {
                    false
                } else {
                    modelClosed = true
                    true
                }
            }
            if (!shouldClose) {
                return
            }
            runCatching { model.close() }
                .onFailure { exception ->
                    sessionLog.record(
                        SOURCE,
                        "Model close failed: ${exception.message ?: exception::class.java.simpleName}"
                    )
                }
            scope.cancel()
        }
    }
}

internal fun sanitizeCaption(rawCaption: String): String {
    val firstLine = rawCaption
        .lineSequence()
        .firstOrNull()
        ?.trim()
        ?.trimSurroundingQuotes()
        ?.trim()
        ?.trimEnd('.')
        ?.trim()
        .orEmpty()
    if (firstLine.isBlank()) {
        return ""
    }
    return firstLine
        .replaceFirstChar { character -> character.lowercase() }
        .take(MAX_CAPTION_LENGTH)
}

internal class LiteRtGemmaCaptionModel(
    private val modelPath: String,
    private val cacheDir: String
) : GemmaCaptionModel {
    private val modelLock = Any()
    private var engine: Engine? = null
    private var conversation: Conversation? = null
    private var closed = false

    override suspend fun caption(jpegBytes: ByteArray): String {
        return synchronized(modelLock) {
            check(!closed) { "Gemma caption model is closed" }
            val response = conversation().sendMessage(
                Contents.of(
                    Content.ImageBytes(jpegBytes),
                    Content.Text(CAPTION_PROMPT)
                )
            )
            response.contents.contents
                .filterIsInstance<Content.Text>()
                .joinToString(separator = " ") { content -> content.text }
        }
    }

    override fun close() {
        synchronized(modelLock) {
            closed = true
            runCatching { conversation?.close() }
            runCatching { engine?.close() }
            conversation = null
            engine = null
        }
    }

    private fun conversation(): Conversation {
        return conversation ?: engine().createConversation(
            ConversationConfig(
                systemInstruction = Contents.of(Content.Text(SYSTEM_INSTRUCTION)),
                samplerConfig = SamplerConfig(
                    topK = 1,
                    topP = 0.95,
                    temperature = 0.2,
                    seed = 0
                )
            )
        ).also { conversation = it }
    }

    private fun engine(): Engine {
        return engine ?: Engine(
            EngineConfig(
                modelPath = modelPath,
                backend = Backend.CPU(),
                visionBackend = Backend.CPU(),
                audioBackend = Backend.CPU(),
                maxNumTokens = MAX_NUM_TOKENS,
                maxNumImages = MAX_NUM_IMAGES,
                cacheDir = cacheDir
            )
        ).also { newEngine ->
            if (!newEngine.isInitialized()) {
                newEngine.initialize()
            }
            engine = newEngine
        }
    }
}

private fun ByteBuffer.copyRemainingBytes(): ByteArray {
    val copy = asReadOnlyBuffer()
    return ByteArray(copy.remaining()).also(copy::get)
}

private fun String.trimSurroundingQuotes(): String {
    return trim()
        .removeSurrounding("\"")
        .removeSurrounding("'")
        .removeSurrounding("\u201c", "\u201d")
        .removeSurrounding("\u2018", "\u2019")
}

private const val DEFAULT_MIN_CAPTION_INTERVAL_MILLIS = 3_000L
private const val MAX_CAPTION_LENGTH = 96
private const val MAX_NUM_TOKENS = 64
private const val MAX_NUM_IMAGES = 1
private const val SOURCE = "LiteRtGemmaFrameCaptioner"
private const val SYSTEM_INSTRUCTION = "You write concise live camera captions."
private const val CAPTION_PROMPT =
    "Describe this camera frame in one short plain caption. " +
        "Use no more than eight words. Do not mention that it is an image."
