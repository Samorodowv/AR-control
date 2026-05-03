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
                .setMinFaceSize(0.15f)
                .build()
        )
        private val closed = AtomicBoolean(false)
        private val frameLock = Object()
        private val frameAdmissionGate = FaceFrameAdmissionGate(MIN_FRAME_INTERVAL_NANOS)
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
            val result = enrollmentController.rememberCurrentFace(currentState, accessStatus)
            if (result is FaceEnrollmentResult.Remembered) {
                currentState = currentState.copy(
                    matchedFace = result.face,
                    faceBoxes = currentState.faceBoxes.map { box ->
                        box.copy(accessStatus = result.face.accessStatus)
                    },
                    status = FaceRecognitionStatus.RememberedFace
                )
                onStateUpdated(currentState)
            }
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
                val faces = Tasks.await(detector.process(inputImage))
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
                if (preEmbeddingState != null) {
                    identityStabilizer.decide(null)
                }
                val nextState = preEmbeddingState ?: run {
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
                        val embedding = model.embed(faceBitmap)
                        val match = matcher.findBestMatch(embedding, embeddingStore.loadAll())
                        val stableFace = identityStabilizer.decide(match)
                        FaceRecognitionState(
                            modelReady = true,
                            faceCount = 1,
                            bestFaceEmbedding = embedding,
                            matchedFace = stableFace,
                            faceBoxes = listOf(
                                stableBox.copy(accessStatus = stableFace?.accessStatus)
                            ),
                            status = if (stableFace == null) {
                                FaceRecognitionStatus.UnknownFace
                            } else {
                                FaceRecognitionStatus.RememberedFace
                            }
                        )
                    } finally {
                        faceBitmap.recycle()
                    }
                }
                if (!closed.get()) {
                    currentState = nextState
                    onStateUpdated(nextState)
                }
            }.onFailure { error ->
                identityStabilizer.decide(null)
                sessionLog.record(TAG, "Face recognition frame failed: ${error.message ?: error::class.java.simpleName}")
            }
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
    }
}
