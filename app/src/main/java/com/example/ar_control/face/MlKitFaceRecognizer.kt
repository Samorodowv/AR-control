package com.example.ar_control.face

import android.content.Context
import android.graphics.Rect
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.detection.DetectionBoundingBox
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import com.example.ar_control.gemma.Yuv420SpImageEncoder
import com.example.ar_control.recording.RecordingInputTarget
import com.example.ar_control.recording.VideoFrameConsumer
import com.example.ar_control.recording.VideoFramePixelFormat
import com.google.android.gms.tasks.Tasks
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.Face
import com.google.mlkit.vision.face.FaceDetection
import com.google.mlkit.vision.face.FaceDetector
import com.google.mlkit.vision.face.FaceDetectorOptions
import java.nio.ByteBuffer
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

internal fun preEmbeddingFaceRecognitionState(
    acceptedCandidateCount: Int,
    stableBoxes: List<FaceBoundingBox>
): FaceRecognitionState? {
    return when {
        acceptedCandidateCount > 1 || stableBoxes.size > 1 -> FaceRecognitionState(
            modelReady = true,
            faceCount = maxOf(acceptedCandidateCount, stableBoxes.size),
            faceBoxes = stableBoxes,
            status = FaceRecognitionStatus.MultipleFaces
        )

        stableBoxes.isEmpty() -> FaceRecognitionState(
            modelReady = true,
            faceCount = 0,
            faceBoxes = emptyList(),
            status = FaceRecognitionStatus.NoFace
        )

        else -> null
    }
}

internal fun faceRecognitionFailureState(
    acceptedFrameCount: Long,
    rejectedFrameCount: Long
): FaceRecognitionState {
    return FaceRecognitionState(
        modelReady = true,
        faceCount = 0,
        faceBoxes = emptyList(),
        status = FaceRecognitionStatus.NoFace,
        lastDetectionMillis = 0L,
        lastEmbeddingMillis = 0L,
        acceptedFrameCount = acceptedFrameCount,
        rejectedFrameCount = rejectedFrameCount
    )
}

internal class FacePipelineDiagnosticLogGate(
    private val logEveryAcceptedFrames: Long
) {
    private var lastLoggedAcceptedFrameCount: Long = 0L

    init {
        require(logEveryAcceptedFrames > 0L) { "logEveryAcceptedFrames must be positive" }
    }

    fun crossedThresholds(acceptedFrameCount: Long): List<Long> {
        val thresholds = mutableListOf<Long>()
        var nextLogThreshold = lastLoggedAcceptedFrameCount + logEveryAcceptedFrames
        while (nextLogThreshold > 0L && nextLogThreshold <= acceptedFrameCount) {
            thresholds += nextLogThreshold
            nextLogThreshold += logEveryAcceptedFrames
        }
        if (thresholds.isNotEmpty()) {
            lastLoggedAcceptedFrameCount = thresholds.last()
        }
        return thresholds
    }
}

internal class FaceStatePublicationGate {
    private var latestToken: Long = 0L

    @Synchronized
    fun nextToken(): Long {
        latestToken++
        return latestToken
    }

    @Synchronized
    fun emitIfCurrent(token: Long, emit: () -> Unit): Boolean {
        if (token <= 0L || token != latestToken) {
            return false
        }
        // Hold this monitor through emission so another thread cannot issue a
        // newer token between the current-token check and the callback/log work.
        emit()
        return true
    }
}

class MlKitFaceRecognizer(
    context: Context,
    private val embeddingStore: FaceEmbeddingStore,
    private val matcher: FaceEmbeddingMatcher = FaceEmbeddingMatcher(),
    private val modelAssetPath: String = LiteRtFaceEmbeddingModel.DEFAULT_MODEL_ASSET_PATH,
    private val sessionLog: SessionLog = NoOpSessionLog
) : FaceRecognizer {
    private val appContext = context.applicationContext

    override fun start(
        previewSize: PreviewSize,
        onStateUpdated: (FaceRecognitionState) -> Unit
    ): FaceRecognitionSession {
        val model = runCatching {
            LiteRtFaceEmbeddingModel.openOrNull(appContext, modelAssetPath)
        }.getOrElse { error ->
            sessionLog.record(TAG, "Face model open failed: ${error.message ?: error::class.java.simpleName}")
            null
        }
        if (model == null) {
            val state = FaceRecognitionState(modelReady = false)
            onStateUpdated(state)
            return MissingModelFaceRecognitionSession(embeddingStore, state)
        }
        return MlKitFaceRecognitionSession(
            previewSize = previewSize,
            model = model,
            embeddingStore = embeddingStore,
            matcher = matcher,
            onStateUpdated = onStateUpdated,
            sessionLog = sessionLog
        )
    }

    private class MlKitFaceRecognitionSession(
        private val previewSize: PreviewSize,
        private val model: FaceEmbeddingModel,
        private val embeddingStore: FaceEmbeddingStore,
        private val matcher: FaceEmbeddingMatcher,
        private val onStateUpdated: (FaceRecognitionState) -> Unit,
        private val sessionLog: SessionLog
    ) : FaceRecognitionSession {
        private val enrollmentController = FaceEnrollmentController(embeddingStore)
        private val faceBoxFilter = FaceBoxFilter()
        private val faceBoxStabilizer = FaceBoxStabilizer()
        private val identityStabilizer = FaceIdentityStabilizer()
        private val detector: FaceDetector = FaceDetection.getClient(
            FaceDetectorOptions.Builder()
                .setPerformanceMode(FaceDetectorOptions.PERFORMANCE_MODE_ACCURATE)
                .setMinFaceSize(0.08f)
                .build()
        )
        private val closed = AtomicBoolean(false)
        private val frameLock = Object()
        private val stateLock = Any()
        private val emissionLock = Any()
        private val frameAdmissionGate = FaceFrameAdmissionGate(MIN_FRAME_INTERVAL_NANOS)
        private val diagnosticLogGate = FacePipelineDiagnosticLogGate(LOG_EVERY_ACCEPTED_FRAMES)
        private val publicationGate = FaceStatePublicationGate()
        private val workerThread = Thread(::runLoop, WORKER_THREAD_NAME).apply {
            isDaemon = true
            start()
        }

        @Volatile
        private var latestPendingFrame: PendingFrame? = null

        @Volatile
        override var currentState: FaceRecognitionState = FaceRecognitionState(
            modelReady = true,
            status = FaceRecognitionStatus.NoFace
        )
            private set

        override val inputTarget: RecordingInputTarget.FrameCallbackTarget =
            RecordingInputTarget.FrameCallbackTarget(
                pixelFormat = VideoFramePixelFormat.YUV420SP,
                minimumFrameIntervalNanos = MIN_FRAME_INTERVAL_NANOS,
                frameConsumer = VideoFrameConsumer { frame, timestampNanos ->
                    enqueueFrame(frame, timestampNanos)
                }
            )

        init {
            onStateUpdated(currentState)
        }

        override fun rememberCurrentFace(accessStatus: FaceAccessStatus): FaceEnrollmentResult {
            var publication: StatePublication? = null
            val result = synchronized(stateLock) {
                val baseState = currentState
                val result = enrollmentController.rememberCurrentFace(baseState, accessStatus)
                if (result is FaceEnrollmentResult.Remembered) {
                    identityStabilizer.seedStableFace(result.face)
                    val updatedState = baseState.copy(
                        matchedFace = result.face,
                        faceBoxes = baseState.faceBoxes.map { box ->
                            box.copy(accessStatus = result.face.accessStatus)
                        },
                        status = FaceRecognitionStatus.RememberedFace
                    )
                    currentState = updatedState
                    publication = StatePublication(
                        token = publicationGate.nextToken(),
                        state = updatedState,
                        logDiagnostics = false
                    )
                }
                result
            }
            publication?.emitIfCurrent()
            return result
        }

        override fun close() {
            if (!closed.compareAndSet(false, true)) {
                return
            }
            synchronized(frameLock) {
                latestPendingFrame = null
                frameLock.notifyAll()
            }
            workerThread.interrupt()
        }

        private fun enqueueFrame(frame: ByteBuffer, timestampNanos: Long) {
            if (closed.get()) {
                return
            }
            val bytes = frameAdmissionGate.runIfAccepted(timestampNanos) {
                frame.asReadOnlyBuffer().let { buffer ->
                    ByteArray(buffer.remaining()).also(buffer::get)
                }
            } ?: return
            synchronized(frameLock) {
                if (closed.get()) {
                    return
                }
                latestPendingFrame = PendingFrame(bytes, timestampNanos)
                frameLock.notifyAll()
            }
        }

        private fun runLoop() {
            try {
                while (true) {
                    val frame = awaitNextFrame() ?: return
                    processFrame(frame)
                }
            } finally {
                runCatching { detector.close() }
                runCatching { model.close() }
            }
        }

        private fun awaitNextFrame(): PendingFrame? {
            try {
                synchronized(frameLock) {
                    while (!closed.get() && latestPendingFrame == null) {
                        frameLock.wait()
                    }
                    if (closed.get()) {
                        return null
                    }
                    return latestPendingFrame.also {
                        latestPendingFrame = null
                    }
                }
            } catch (_: InterruptedException) {
                return null
            }
        }

        private fun processFrame(frame: PendingFrame) {
            runCatching {
                val nv21 = Yuv420SpImageEncoder.convertUvOrderYuv420SpToNv21(frame.bytes, previewSize)
                val inputImage = InputImage.fromByteArray(
                    nv21,
                    previewSize.width,
                    previewSize.height,
                    previewSize.normalizedRotationDegrees,
                    InputImage.IMAGE_FORMAT_NV21
                )
                val detectionStartNanos = System.nanoTime()
                val faces = Tasks.await(detector.process(inputImage))
                val detectionMillis = elapsedMillisSince(detectionStartNanos)
                val acceptedCandidates = faces
                    .map { face -> face to face.toFaceBox(accessStatus = null) }
                    .filter { (_, box) -> faceBoxFilter.isAccepted(box.boundingBox, previewSize) }
                val stableBoxes = faceBoxStabilizer.update(
                    acceptedCandidates.map { (_, box) -> box }
                )
                val preEmbeddingState = preEmbeddingFaceRecognitionState(
                    acceptedCandidateCount = acceptedCandidates.size,
                    stableBoxes = stableBoxes
                )
                var embeddingMillis = 0L
                val embeddingCandidate = if (preEmbeddingState == null) {
                    val face = acceptedCandidates.single().first
                    val stableBox = stableBoxes.single()
                    val faceBitmap = Nv21FaceBitmapCropper.cropToBitmap(
                        nv21 = nv21,
                        previewSize = previewSize,
                        bounds = face.boundingBox.padded(
                            scale = 0.18f,
                            maxWidth = previewSize.width,
                            maxHeight = previewSize.height
                        )
                    )
                    try {
                        val embeddingStartNanos = System.nanoTime()
                        val embedding = model.embed(faceBitmap)
                        embeddingMillis = elapsedMillisSince(embeddingStartNanos)
                        EmbeddingCandidate(
                            embedding = embedding,
                            stableBox = stableBox
                        )
                    } finally {
                        faceBitmap.recycle()
                    }
                } else {
                    null
                }
                if (!closed.get()) {
                    val publication = synchronized(stateLock) {
                        if (!closed.get()) {
                            val nextState = if (preEmbeddingState == null) {
                                embeddingCandidate.toRecognitionStateLocked()
                            } else {
                                identityStabilizer.decide(null)
                                preEmbeddingState
                            }
                            val diagnosticState = nextState.withDiagnostics(
                                detectionMillis = detectionMillis,
                                embeddingMillis = embeddingMillis
                            )
                            currentState = diagnosticState
                            StatePublication(
                                token = publicationGate.nextToken(),
                                state = diagnosticState,
                                logDiagnostics = true
                            )
                        } else {
                            null
                        }
                    }
                    publication?.emitIfCurrent()
                }
            }.onFailure { error ->
                val acceptedFrameCount = frameAdmissionGate.acceptedCount
                val rejectedFrameCount = frameAdmissionGate.rejectedCount
                val publication = if (!closed.get()) {
                    val failureState = faceRecognitionFailureState(
                        acceptedFrameCount = acceptedFrameCount,
                        rejectedFrameCount = rejectedFrameCount
                    )
                    synchronized(stateLock) {
                        if (!closed.get()) {
                            identityStabilizer.decide(null)
                            currentState = failureState
                            StatePublication(
                                token = publicationGate.nextToken(),
                                state = failureState,
                                logDiagnostics = false
                            )
                        } else {
                            null
                        }
                    }
                } else {
                    null
                }
                publication?.emitIfCurrent()
                sessionLog.record(
                    TAG,
                    "Face recognition frame failed accepted=$acceptedFrameCount rejected=$rejectedFrameCount: ${error.message ?: error::class.java.simpleName}"
                )
            }
        }

        private fun EmbeddingCandidate?.toRecognitionStateLocked(): FaceRecognitionState {
            val candidate = checkNotNull(this) { "Embedding candidate is required for single-face state" }
            val match = matcher.findBestMatch(candidate.embedding, embeddingStore.loadAll())
            val stableFace = identityStabilizer.decide(
                match = match,
                retainStableIdentityOnMiss = true
            )
            return FaceRecognitionState(
                modelReady = true,
                faceCount = 1,
                bestFaceEmbedding = candidate.embedding,
                matchedFace = stableFace,
                faceBoxes = listOf(
                    candidate.stableBox.copy(accessStatus = stableFace?.accessStatus)
                ),
                status = if (stableFace == null) {
                    FaceRecognitionStatus.UnknownFace
                } else {
                    FaceRecognitionStatus.RememberedFace
                }
            )
        }

        private fun FaceRecognitionState.withDiagnostics(
            detectionMillis: Long,
            embeddingMillis: Long
        ): FaceRecognitionState {
            return copy(
                lastDetectionMillis = detectionMillis,
                lastEmbeddingMillis = embeddingMillis,
                acceptedFrameCount = frameAdmissionGate.acceptedCount,
                rejectedFrameCount = frameAdmissionGate.rejectedCount
            )
        }

        private fun StatePublication.emitIfCurrent() {
            synchronized(emissionLock) {
                publicationGate.emitIfCurrent(token) {
                    onStateUpdated(state)
                    if (logDiagnostics) {
                        for (threshold in diagnosticLogGate.crossedThresholds(state.acceptedFrameCount)) {
                            sessionLog.record(
                                TAG,
                                state.diagnosticLogMessage(threshold)
                            )
                        }
                    }
                }
            }
        }

        private fun FaceRecognitionState.diagnosticLogMessage(threshold: Long): String {
            return "Face pipeline threshold=$threshold accepted=$acceptedFrameCount rejected=$rejectedFrameCount detectMs=$lastDetectionMillis embedMs=$lastEmbeddingMillis faces=$faceCount"
        }

        private fun elapsedMillisSince(startNanos: Long): Long {
            return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos)
        }

        private fun Face.toFaceBox(accessStatus: FaceAccessStatus?): FaceBoundingBox {
            val bounds = boundingBox
            return FaceBoundingBox(
                boundingBox = DetectionBoundingBox(
                    left = bounds.left.toFloat(),
                    top = bounds.top.toFloat(),
                    right = bounds.right.toFloat(),
                    bottom = bounds.bottom.toFloat()
                ),
                accessStatus = accessStatus
            )
        }

        private fun Rect.padded(scale: Float, maxWidth: Int, maxHeight: Int): Rect {
            val horizontalPadding = (width() * scale).toInt()
            val verticalPadding = (height() * scale).toInt()
            return Rect(
                (left - horizontalPadding).coerceAtLeast(0),
                (top - verticalPadding).coerceAtLeast(0),
                (right + horizontalPadding).coerceAtMost(maxWidth),
                (bottom + verticalPadding).coerceAtMost(maxHeight)
            )
        }

        private data class PendingFrame(
            val bytes: ByteArray,
            val timestampNanos: Long
        )

        private data class EmbeddingCandidate(
            val embedding: FaceEmbedding,
            val stableBox: FaceBoundingBox
        )

        private data class StatePublication(
            val token: Long,
            val state: FaceRecognitionState,
            val logDiagnostics: Boolean
        )
    }

    private class MissingModelFaceRecognitionSession(
        embeddingStore: FaceEmbeddingStore,
        override val currentState: FaceRecognitionState
    ) : FaceRecognitionSession {
        private val enrollmentController = FaceEnrollmentController(embeddingStore)

        override val inputTarget: RecordingInputTarget.FrameCallbackTarget =
            RecordingInputTarget.FrameCallbackTarget(
                pixelFormat = VideoFramePixelFormat.YUV420SP,
                frameConsumer = VideoFrameConsumer { _, _ -> }
            )

        override fun rememberCurrentFace(accessStatus: FaceAccessStatus): FaceEnrollmentResult {
            return enrollmentController.rememberCurrentFace(currentState, accessStatus)
        }

        override fun close() = Unit
    }

    private companion object {
        const val TAG = "MlKitFaceRecognizer"
        const val WORKER_THREAD_NAME = "face-recognition"
        const val MIN_FRAME_INTERVAL_NANOS = 250_000_000L
        const val LOG_EVERY_ACCEPTED_FRAMES = 30L
    }
}
