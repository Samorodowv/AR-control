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
import com.google.ai.edge.litertlm.Message
import com.google.ai.edge.litertlm.MessageCallback
import com.google.ai.edge.litertlm.SamplerConfig
import java.nio.ByteBuffer
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.asCoroutineDispatcher
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

internal interface GemmaCaptionModel : AutoCloseable {
    suspend fun caption(
        imageBytes: ByteArray,
        prompt: String,
        onPartialCaption: (String) -> Unit
    ): String

    override fun close()
}

class LiteRtGemmaFrameCaptioner internal constructor(
    private val modelFactory: (String) -> GemmaCaptionModel,
    private val dispatcher: CoroutineDispatcher,
    private val minCaptionIntervalMillis: Long = DEFAULT_MIN_CAPTION_INTERVAL_MILLIS,
    private val captionPromptProvider: () -> String = { DEFAULT_GEMMA_CAPTION_PROMPT },
    private val minPartialCaptionUpdateIntervalMillis: Long = DEFAULT_MIN_PARTIAL_CAPTION_UPDATE_INTERVAL_MILLIS,
    private val clock: () -> Long = System::currentTimeMillis,
    private val sessionLog: SessionLog = NoOpSessionLog
) : GemmaFrameCaptioner {

    constructor(
        context: Context,
        captionPromptProvider: () -> String = { DEFAULT_GEMMA_CAPTION_PROMPT },
        sessionLog: SessionLog = NoOpSessionLog
    ) : this(
        modelFactory = { modelPath ->
            LiteRtGemmaCaptionModel(modelPath = modelPath)
        },
        dispatcher = GEMMA_INFERENCE_DISPATCHER,
        captionPromptProvider = captionPromptProvider,
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
            captionPromptProvider = captionPromptProvider,
            minPartialCaptionUpdateIntervalMillis = minPartialCaptionUpdateIntervalMillis,
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
        private val captionPromptProvider: () -> String,
        private val minPartialCaptionUpdateIntervalMillis: Long,
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
                minimumFrameIntervalNanos = minCaptionIntervalMillis * NANOS_PER_MILLISECOND,
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
                    val imageBytes = Yuv420SpImageEncoder.encodePngForGemma(
                        frameBytes,
                        previewSize,
                        maxDimension = GEMMA_IMAGE_MAX_DIMENSION
                    )
                    if (closed.get()) {
                        return@launch
                    }
                    val lastPublishedCaption = AtomicReference("")
                    val lastPublishedCaptionAtMillis = AtomicLong(Long.MIN_VALUE)
                    val prompt = captionPromptProvider().ifBlank { DEFAULT_GEMMA_CAPTION_PROMPT }
                    val caption = model.caption(imageBytes, prompt) { partialCaption ->
                        publishSanitizedCaption(
                            rawCaption = partialCaption,
                            closed = closed,
                            lastPublishedCaption = lastPublishedCaption,
                            lastPublishedCaptionAtMillis = lastPublishedCaptionAtMillis,
                            minPartialCaptionUpdateIntervalMillis = minPartialCaptionUpdateIntervalMillis,
                            clock = clock,
                            force = false,
                            onCaptionUpdated = onCaptionUpdated
                        )
                    }
                    publishSanitizedCaption(
                        rawCaption = caption,
                        closed = closed,
                        lastPublishedCaption = lastPublishedCaption,
                        lastPublishedCaptionAtMillis = lastPublishedCaptionAtMillis,
                        minPartialCaptionUpdateIntervalMillis = minPartialCaptionUpdateIntervalMillis,
                        clock = clock,
                        force = true,
                        onCaptionUpdated = onCaptionUpdated
                    )
                } catch (exception: CancellationException) {
                    throw exception
                } catch (exception: Exception) {
                    val reason = exception.message ?: exception::class.java.simpleName
                    sessionLog.record(SOURCE, "Caption failed: $reason")
                    if (!closed.get()) {
                        onError(reason)
                    }
                } finally {
                    lastAcceptedAtMillis = clock()
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

private fun publishSanitizedCaption(
    rawCaption: String,
    closed: AtomicBoolean,
    lastPublishedCaption: AtomicReference<String>,
    lastPublishedCaptionAtMillis: AtomicLong,
    minPartialCaptionUpdateIntervalMillis: Long,
    clock: () -> Long,
    force: Boolean,
    onCaptionUpdated: (String) -> Unit
) {
    if (closed.get()) {
        return
    }
    val caption = sanitizeCaption(rawCaption)
    if (caption.isBlank()) {
        return
    }
    val now = clock()
    if (!force) {
        val previousPublishedAt = lastPublishedCaptionAtMillis.get()
        if (
            previousPublishedAt != Long.MIN_VALUE &&
            now - previousPublishedAt < minPartialCaptionUpdateIntervalMillis
        ) {
            return
        }
    }
    while (true) {
        val previousCaption = lastPublishedCaption.get()
        if (caption == previousCaption) {
            return
        }
        if (lastPublishedCaption.compareAndSet(previousCaption, caption)) {
            lastPublishedCaptionAtMillis.set(now)
            onCaptionUpdated(caption)
            return
        }
    }
}

internal fun sanitizeCaption(rawCaption: String): String {
    val caption = rawCaption
        .lineSequence()
        .map { line -> line.trim() }
        .filter { line -> line.isNotBlank() }
        .joinToString(separator = " ")
        .replace(SPACING_AROUND_HYPHEN_REGEX, "-")
        .replace(MULTIPLE_WHITESPACE_REGEX, " ")
        .joinRussianSuffixFragments()
        .trimSurroundingQuotes()
        .trim()
        .trimEnd('.')
        .trim()
    if (caption.isBlank()) {
        return ""
    }
    return caption
        .replaceFirstChar { character -> character.lowercase() }
        .take(MAX_CAPTION_LENGTH)
}

internal class LiteRtGemmaCaptionModel(
    private val modelPath: String
) : GemmaCaptionModel {
    private val modelLock = Any()
    private var engine: Engine? = null
    private var closed = false

    override suspend fun caption(
        imageBytes: ByteArray,
        prompt: String,
        onPartialCaption: (String) -> Unit
    ): String {
        val conversation = synchronized(modelLock) {
            check(!closed) { "Gemma caption model is closed" }
            createConversation()
        }
        return try {
            conversation.sendCaptionRequest(
                imageBytes = imageBytes,
                prompt = prompt,
                onPartialCaption = onPartialCaption
            )
        } finally {
            runCatching { conversation.close() }
        }
    }

    override fun close() {
        synchronized(modelLock) {
            closed = true
            runCatching { engine?.close() }
            engine = null
        }
    }

    private fun createConversation(): Conversation {
        return engine().createConversation(
            ConversationConfig(
                samplerConfig = SamplerConfig(
                    topK = SAMPLER_TOP_K,
                    topP = SAMPLER_TOP_P,
                    temperature = SAMPLER_TEMPERATURE
                )
            )
        )
    }

    private fun engine(): Engine {
        return engine ?: Engine(
            createGemmaEngineConfig(modelPath = modelPath)
        ).also { newEngine ->
            if (!newEngine.isInitialized()) {
                newEngine.initialize()
            }
            engine = newEngine
        }
    }
}

private suspend fun Conversation.sendCaptionRequest(
    imageBytes: ByteArray,
    prompt: String,
    onPartialCaption: (String) -> Unit
): String =
    suspendCancellableCoroutine { continuation ->
        val responseText = StringBuilder()
        val contents = Contents.of(
            Content.ImageBytes(imageBytes),
            Content.Text(prompt)
        )
        sendMessageAsync(
            contents,
            object : MessageCallback {
                override fun onMessage(message: Message) {
                    val chunks = message.contents.contents
                        .filterIsInstance<Content.Text>()
                        .map { content -> content.text }
                    responseText.append(combineGemmaTextChunks(chunks))
                    if (continuation.isActive) {
                        onPartialCaption(responseText.toString())
                    }
                }

                override fun onDone() {
                    if (continuation.isActive) {
                        continuation.resume(responseText.toString())
                    }
                }

                override fun onError(throwable: Throwable) {
                    if (continuation.isActive) {
                        continuation.resumeWithException(throwable)
                    }
                }
            },
            emptyMap()
        )
        continuation.invokeOnCancellation { cancelProcess() }
    }

internal fun combineGemmaTextChunks(chunks: List<String>): String {
    return chunks.joinToString(separator = "")
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

private fun String.joinRussianSuffixFragments(): String {
    val tokens = split(' ')
    if (tokens.size < 2) {
        return this
    }
    val output = mutableListOf<String>()
    for (token in tokens) {
        val previous = output.lastOrNull()
        if (previous != null && shouldJoinRussianSuffixFragment(previous, token)) {
            output[output.lastIndex] = previous + token
        } else {
            output += token
        }
    }
    return output.joinToString(separator = " ")
}

private fun shouldJoinRussianSuffixFragment(previous: String, fragment: String): Boolean {
    val previousWord = previous.trimSurroundingQuotes()
    val fragmentWord = fragment.trimEnd { character -> character in RUSSIAN_TRAILING_PUNCTUATION }
    if (
        previousWord.length < MIN_RUSSIAN_FRAGMENT_PREFIX_LENGTH ||
        previousWord.lowercase() in RUSSIAN_STOP_WORDS
    ) {
        return false
    }
    return previousWord.matches(RUSSIAN_WORD_REGEX) &&
        fragmentWord.matches(RUSSIAN_WORD_REGEX) &&
        fragmentWord.lowercase() in RUSSIAN_SUFFIX_FRAGMENTS
}

internal fun createGemmaEngineConfig(
    modelPath: String
): EngineConfig {
    return EngineConfig(
        modelPath = modelPath,
        backend = Backend.GPU(),
        visionBackend = Backend.GPU(),
        audioBackend = null,
        maxNumTokens = ENGINE_MAX_NUM_TOKENS,
        cacheDir = null
    )
}

private const val DEFAULT_MIN_CAPTION_INTERVAL_MILLIS = 3_000L
private const val DEFAULT_MIN_PARTIAL_CAPTION_UPDATE_INTERVAL_MILLIS = 80L
private const val NANOS_PER_MILLISECOND = 1_000_000L
private const val GEMMA_IMAGE_MAX_DIMENSION = 512
private const val MAX_CAPTION_LENGTH = 1_000
private const val ENGINE_MAX_NUM_TOKENS = 1024
private const val SAMPLER_TOP_K = 64
private const val SAMPLER_TOP_P = 0.95
private const val SAMPLER_TEMPERATURE = 1.0
private const val SOURCE = "LiteRtGemmaFrameCaptioner"
private val SPACING_AROUND_HYPHEN_REGEX = Regex("\\s+-\\s+")
private val MULTIPLE_WHITESPACE_REGEX = Regex("\\s+")
private val RUSSIAN_WORD_REGEX = Regex("^[А-Яа-яЁё]+$")
private const val MIN_RUSSIAN_FRAGMENT_PREFIX_LENGTH = 3
private const val RUSSIAN_TRAILING_PUNCTUATION = ".,;:!?…"
private val RUSSIAN_STOP_WORDS = setOf(
    "и",
    "или",
    "в",
    "во",
    "на",
    "с",
    "со",
    "к",
    "ко",
    "у",
    "от",
    "до",
    "за",
    "по",
    "под",
    "над",
    "из",
    "о",
    "об",
    "без",
    "для",
    "как",
    "это",
    "не"
)
private val RUSSIAN_SUFFIX_FRAGMENTS = setOf(
    "ый",
    "ий",
    "ой",
    "ая",
    "яя",
    "ое",
    "ее",
    "ые",
    "ие",
    "ую",
    "юю",
    "ого",
    "его",
    "ому",
    "ему",
    "ым",
    "им",
    "ых",
    "их",
    "ыми",
    "ими",
    "ный",
    "ний",
    "ная",
    "нее",
    "ное",
    "ные",
    "ную",
    "ной",
    "ных",
    "ным",
    "ными",
    "ка",
    "ку",
    "ки",
    "ке",
    "кой"
)
internal const val CAPTION_PROMPT = DEFAULT_GEMMA_CAPTION_PROMPT
private val GEMMA_INFERENCE_DISPATCHER = Executors.newSingleThreadExecutor { runnable ->
    Thread(runnable, "gemma-caption-inference").apply {
        isDaemon = true
        priority = Thread.MIN_PRIORITY
    }
}.asCoroutineDispatcher()
