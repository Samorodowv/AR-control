package com.example.ar_control.recording

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.detection.DetectedObject
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import java.io.File
import java.util.UUID
import java.util.concurrent.LinkedBlockingDeque
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

internal fun interface Yuv420FrameConverter {
    fun convert(source: ByteArray, destination: ByteArray): Int

    companion object {
        fun forColorFormat(
            width: Int,
            height: Int,
            sourcePixelFormat: VideoFramePixelFormat,
            colorFormat: Int
        ): Yuv420FrameConverter {
            val frameSize = width * height
            val chromaPlaneSize = frameSize / 4
            return when (colorFormat) {
                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar -> Yuv420FrameConverter { source, destination ->
                    System.arraycopy(source, 0, destination, 0, source.size)
                    if (sourcePixelFormat == VideoFramePixelFormat.NV21) {
                        var index = frameSize
                        while (index < source.size) {
                            val v = source[index]
                            val u = source[index + 1]
                            destination[index] = u
                            destination[index + 1] = v
                            index += 2
                        }
                    }
                    source.size
                }

                MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar -> Yuv420FrameConverter { source, destination ->
                    System.arraycopy(source, 0, destination, 0, frameSize)
                    var sourceIndex = frameSize
                    var uIndex = frameSize
                    var vIndex = frameSize + chromaPlaneSize
                    repeat(chromaPlaneSize) {
                        when (sourcePixelFormat) {
                            VideoFramePixelFormat.YUV420SP -> {
                                destination[uIndex++] = source[sourceIndex++]
                                destination[vIndex++] = source[sourceIndex++]
                            }

                            VideoFramePixelFormat.NV21 -> {
                                destination[vIndex++] = source[sourceIndex++]
                                destination[uIndex++] = source[sourceIndex++]
                            }
                        }
                    }
                    frameSize + (chromaPlaneSize * 2)
                }

                else -> throw IllegalArgumentException("unsupported_color_format_$colorFormat")
            }
        }
    }
}

internal val DEFAULT_RECORDER_FRAME_CALLBACK_PIXEL_FORMAT = VideoFramePixelFormat.YUV420SP

class MediaCodecVideoRecorder internal constructor(
    private val outputDirectory: File,
    private val sessionLog: SessionLog = NoOpSessionLog,
    private val clock: () -> Long = { System.currentTimeMillis() },
    private val fileNameFactory: () -> String = {
        "clip-${System.currentTimeMillis()}-${UUID.randomUUID()}.mp4"
    },
    private val engineFactory: RecorderEngineFactory = AndroidRecorderEngineFactory
) : VideoRecorder, DetectionAnnotationSink {

    private val mutex = Mutex()
    private var activeSession: RecordingSession? = null
    private val detectionAnnotationSnapshot = AtomicReference<DetectionAnnotationSnapshot?>(null)

    constructor(
        outputDirectory: File,
        sessionLog: SessionLog = NoOpSessionLog
    ) : this(
        outputDirectory = outputDirectory,
        sessionLog = sessionLog,
        clock = { System.currentTimeMillis() },
        fileNameFactory = {
            "clip-${System.currentTimeMillis()}-${UUID.randomUUID()}.mp4"
        },
        engineFactory = AndroidRecorderEngineFactory
    )

    override fun updateDetections(
        previewSize: PreviewSize,
        detections: List<DetectedObject>
    ) {
        detectionAnnotationSnapshot.set(
            DetectionAnnotationSnapshot(
                previewSize = previewSize,
                detections = detections.toList()
            )
        )
    }

    override fun clearDetections() {
        detectionAnnotationSnapshot.set(null)
    }

    override suspend fun start(previewSize: PreviewSize): VideoRecorder.StartResult =
        withContext(Dispatchers.IO) {
            mutex.withLock {
                if (activeSession != null) {
                    return@withLock VideoRecorder.StartResult.Failed("recording_already_started")
                }

                sessionLog.record(
                    TAG,
                    "Starting recorder for ${previewSize.width}x${previewSize.height}"
                )

                var pendingOutputFile: File? = null
                runCatching {
                    outputDirectory.mkdirs()
                    val outputFile = File(outputDirectory, fileNameFactory())
                    pendingOutputFile = outputFile
                    if (outputFile.exists()) {
                        outputFile.delete()
                    }
                    outputFile.outputStream().use { }

                    val startedAt = clock()
                    val engine = engineFactory.create(
                        outputFile = outputFile,
                        previewSize = previewSize,
                        sessionLog = sessionLog,
                        detectionAnnotationSnapshotProvider = detectionAnnotationSnapshot::get
                    )
                    val session = RecordingSession(
                        outputFile = outputFile,
                        previewSize = previewSize,
                        startedAtEpochMillis = startedAt,
                        engine = engine
                    )
                    activeSession = session
                    sessionLog.record(TAG, "Recording prepared at ${outputFile.absolutePath}")

                    VideoRecorder.StartResult.Started(
                        inputTarget = engine.inputTarget,
                        outputFilePath = outputFile.absolutePath,
                        startedAtEpochMillis = startedAt
                    )
                }.getOrElse { error ->
                    sessionLog.record(TAG, "Recording start failed: ${error.message}")
                    cleanupFailedStart(pendingOutputFile)
                    VideoRecorder.StartResult.Failed(
                        error.message ?: "recording_prepare_failed"
                    )
                }
            }
        }

    override suspend fun stop(): VideoRecorder.StopResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = activeSession
                ?: return@withLock VideoRecorder.StopResult.Failed("recording_not_started")

            sessionLog.record(TAG, "Finalizing recording at ${session.outputFile.absolutePath}")

            runCatching {
                sessionLog.record(TAG, "Invoking recorder engine finalize")
                when (val finalizeResult = session.engine.finalizeRecording()) {
                    is FinalizeResult.FinalizedWithWarning -> {
                        sessionLog.record(TAG, "Recording finalized with warning: ${finalizeResult.message}")
                    }
                    FinalizeResult.Finalized -> Unit
                }
                val finishedAt = clock()
                val clip = RecordedClip(
                    id = session.outputFile.nameWithoutExtension,
                    filePath = session.outputFile.absolutePath,
                    createdAtEpochMillis = session.startedAtEpochMillis,
                    durationMillis = (finishedAt - session.startedAtEpochMillis).coerceAtLeast(0L),
                    width = session.previewSize.width,
                    height = session.previewSize.height,
                    fileSizeBytes = session.outputFile.length(),
                    mimeType = OUTPUT_MIME_TYPE
                )
                activeSession = null
                sessionLog.record(TAG, "Recording finalized at ${session.outputFile.absolutePath}")
                VideoRecorder.StopResult.Finished(clip)
            }.getOrElse { error ->
                sessionLog.record(TAG, "Recording finalize failed: ${error.message}")
                sessionLog.record(TAG, "Cleaning up failed recording session at ${session.outputFile.absolutePath}")
                cleanupSession(session)
                VideoRecorder.StopResult.Failed(
                    error.message ?: "recording_finalize_failed"
                )
            }
        }
    }

    override suspend fun cancel(): VideoRecorder.CancelResult = withContext(Dispatchers.IO) {
        mutex.withLock {
            val session = activeSession
                ?: return@withLock VideoRecorder.CancelResult.Failed("recording_not_started")
            sessionLog.record(TAG, "Cancelling recording at ${session.outputFile.absolutePath}")
            when (val cleanupResult = cleanupSession(session)) {
                CleanupResult.Success -> VideoRecorder.CancelResult.Cancelled
                is CleanupResult.Failed -> VideoRecorder.CancelResult.Failed(cleanupResult.reason)
            }
        }
    }

    private fun cleanupFailedStart(pendingOutputFile: File?) {
        activeSession?.let(::cleanupSession)
        if (activeSession == null) {
            runCatching { pendingOutputFile?.delete() }
        }
    }

    private fun cleanupSession(session: RecordingSession): CleanupResult {
        activeSession = null
        val failures = mutableListOf<String>()

        sessionLog.record(TAG, "Releasing recorder engine for ${session.outputFile.absolutePath}")
        runCatching { session.engine.cancel() }
            .exceptionOrNull()
            ?.message
            ?.let(failures::add)

        sessionLog.record(TAG, "Deleting recording file ${session.outputFile.absolutePath}")
        val deleted = runCatching { session.outputFile.delete() }.getOrDefault(false)
        if (!deleted && session.outputFile.exists()) {
            failures += "recording_file_delete_failed"
        }

        return if (failures.isEmpty()) {
            CleanupResult.Success
        } else {
            CleanupResult.Failed(failures.joinToString(separator = "; "))
        }
    }

    private data class RecordingSession(
        val outputFile: File,
        val previewSize: PreviewSize,
        val startedAtEpochMillis: Long,
        val engine: RecorderEngine
    )

    private sealed interface CleanupResult {
        data object Success : CleanupResult
        data class Failed(val reason: String) : CleanupResult
    }

    internal interface RecorderEngineFactory {
        fun create(
            outputFile: File,
            previewSize: PreviewSize,
            sessionLog: SessionLog,
            detectionAnnotationSnapshotProvider: () -> DetectionAnnotationSnapshot?
        ): RecorderEngine
    }

    internal interface RecorderEngine {
        val inputTarget: RecordingInputTarget

        fun finalizeRecording(): FinalizeResult

        fun cancel()
    }

    internal sealed interface FinalizeResult {
        data object Finalized : FinalizeResult
        data class FinalizedWithWarning(val message: String) : FinalizeResult
    }

    private object AndroidRecorderEngineFactory : RecorderEngineFactory {
        override fun create(
            outputFile: File,
            previewSize: PreviewSize,
            sessionLog: SessionLog,
            detectionAnnotationSnapshotProvider: () -> DetectionAnnotationSnapshot?
        ): RecorderEngine {
            return AndroidRecorderEngine(
                outputFile = outputFile,
                previewSize = previewSize,
                sessionLog = sessionLog,
                detectionAnnotationSnapshotProvider = detectionAnnotationSnapshotProvider
            )
        }
    }

    private class AndroidRecorderEngine(
        outputFile: File,
        previewSize: PreviewSize,
        private val sessionLog: SessionLog,
        private val detectionAnnotationSnapshotProvider: () -> DetectionAnnotationSnapshot?
    ) : RecorderEngine {

        private val codec: MediaCodec
        private val muxer: MediaMuxer
        private var codecStarted = false
        private var muxerStarted = false
        private var trackIndex = -1
        private var lastPresentationTimeUs = 0L
        private val frameWidth = previewSize.width
        private val frameHeight = previewSize.height
        private val sourcePixelFormat = DEFAULT_RECORDER_FRAME_CALLBACK_PIXEL_FORMAT
        private val selectedColorFormat: Int
        private val converter: Yuv420FrameConverter
        private val detectionAnnotationRenderer = YuvDetectionAnnotationRenderer()
        private val frameQueue = LinkedBlockingDeque<FramePacket>(MAX_PENDING_FRAMES)
        private val encodedFrameBuffer = ByteArray(frameWidth * frameHeight * 3 / 2)
        @Volatile
        private var isAcceptingFrames = true
        @Volatile
        private var workerFailure: Throwable? = null
        private val workerThread: Thread

        override val inputTarget: RecordingInputTarget = RecordingInputTarget.FrameCallbackTarget(
            pixelFormat = sourcePixelFormat,
            frameConsumer = VideoFrameConsumer(::offerFrame)
        )

        init {
            var pendingCodec: MediaCodec? = null
            var pendingMuxer: MediaMuxer? = null
            var pendingCodecStarted = false
            try {
                pendingCodec = MediaCodec.createEncoderByType(ENCODER_MIME_TYPE)
                selectedColorFormat = selectColorFormat(pendingCodec)
                converter = Yuv420FrameConverter.forColorFormat(
                    width = frameWidth,
                    height = frameHeight,
                    sourcePixelFormat = sourcePixelFormat,
                    colorFormat = selectedColorFormat
                )
                val format = MediaFormat.createVideoFormat(
                    ENCODER_MIME_TYPE,
                    frameWidth,
                    frameHeight
                ).apply {
                    setInteger(MediaFormat.KEY_COLOR_FORMAT, selectedColorFormat)
                    setInteger(MediaFormat.KEY_BIT_RATE, frameWidth * frameHeight * 4)
                    setInteger(MediaFormat.KEY_FRAME_RATE, FRAME_RATE)
                    setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, I_FRAME_INTERVAL_SECONDS)
                }
                pendingCodec.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
                pendingCodec.start()
                pendingCodecStarted = true
                pendingMuxer = MediaMuxer(
                    outputFile.absolutePath,
                    MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4
                )
            } catch (error: Throwable) {
                releaseConstructionResources(
                    codec = pendingCodec,
                    codecStarted = pendingCodecStarted,
                    muxer = pendingMuxer
                )
                throw error
            }

            codec = checkNotNull(pendingCodec)
            muxer = checkNotNull(pendingMuxer)
            codecStarted = pendingCodecStarted
            workerThread = Thread(::runEncodeLoop, "MediaCodecVideoRecorder-Worker").apply {
                isDaemon = true
                start()
            }
        }

        override fun finalizeRecording(): FinalizeResult {
            isAcceptingFrames = false
            enqueueEndOfStream()
            joinWorkerThread()
            workerFailure?.let { throw it }

            val releaseFailure = releaseResources()
            return if (releaseFailure != null) {
                FinalizeResult.FinalizedWithWarning(
                    releaseFailure.message ?: "recording_release_failed"
                )
            } else {
                FinalizeResult.Finalized
            }
        }

        override fun cancel() {
            isAcceptingFrames = false
            frameQueue.clear()
            workerThread.interrupt()
            joinWorkerThread(throwOnTimeout = false)
            releaseResources()
        }

        private fun offerFrame(frame: java.nio.ByteBuffer, timestampNanos: Long) {
            if (!isAcceptingFrames) {
                return
            }

            val duplicate = frame.duplicate()
            val data = ByteArray(duplicate.remaining())
            duplicate.get(data)
            val packet = FramePacket.Frame(
                data = data,
                presentationTimeUs = TimeUnit.NANOSECONDS.toMicros(timestampNanos)
            )
            if (!frameQueue.offerLast(packet)) {
                frameQueue.pollFirst()
                frameQueue.offerLast(packet)
            }
        }

        private fun enqueueEndOfStream() {
            while (!frameQueue.offerLast(FramePacket.EndOfStream)) {
                frameQueue.pollFirst()
            }
        }

        private fun joinWorkerThread(throwOnTimeout: Boolean = true) {
            workerThread.join(WORKER_JOIN_TIMEOUT_MS)
            if (workerThread.isAlive && throwOnTimeout) {
                throw IllegalStateException("encoder_worker_shutdown_timeout")
            }
        }

        private fun runEncodeLoop() {
            try {
                while (true) {
                    when (val packet = frameQueue.take()) {
                        FramePacket.EndOfStream -> {
                            queueEndOfStream()
                            drainEncoder(endOfStream = true)
                            return
                        }

                        is FramePacket.Frame -> {
                            queueFrame(packet)
                            drainEncoder(endOfStream = false)
                        }
                    }
                }
            } catch (error: InterruptedException) {
                if (isAcceptingFrames) {
                    workerFailure = IllegalStateException("encoder_worker_interrupted", error)
                }
            } catch (error: Throwable) {
                workerFailure = error
            }
        }

        private fun queueFrame(packet: FramePacket.Frame) {
            val inputBufferIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
            if (inputBufferIndex < 0) {
                return
            }

            val presentationTimeUs = normalizePresentationTimeUs(packet.presentationTimeUs)
            val inputBuffer = codec.getInputBuffer(inputBufferIndex)
                ?: throw IllegalStateException("encoder input buffer unavailable")

            detectionAnnotationRenderer.annotate(
                frameBytes = packet.data,
                frameWidth = frameWidth,
                frameHeight = frameHeight,
                snapshot = detectionAnnotationSnapshotProvider()
            )
            val convertedLength = converter.convert(packet.data, encodedFrameBuffer)
            inputBuffer.clear()
            inputBuffer.put(encodedFrameBuffer, 0, convertedLength)
            codec.queueInputBuffer(
                inputBufferIndex,
                0,
                convertedLength,
                presentationTimeUs,
                0
            )
        }

        private fun queueEndOfStream() {
            while (true) {
                val inputBufferIndex = codec.dequeueInputBuffer(DEQUEUE_TIMEOUT_US)
                if (inputBufferIndex >= 0) {
                    val presentationTimeUs = normalizePresentationTimeUs(lastPresentationTimeUs + 1)
                    codec.queueInputBuffer(
                        inputBufferIndex,
                        0,
                        0,
                        presentationTimeUs,
                        MediaCodec.BUFFER_FLAG_END_OF_STREAM
                    )
                    return
                }
            }
        }

        private fun drainEncoder(endOfStream: Boolean) {
            val bufferInfo = MediaCodec.BufferInfo()
            while (true) {
                when (val outputBufferIndex = codec.dequeueOutputBuffer(bufferInfo, DEQUEUE_TIMEOUT_US)) {
                    MediaCodec.INFO_TRY_AGAIN_LATER -> {
                        if (!endOfStream) {
                            return
                        }
                    }

                    MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                        check(!muxerStarted) { "encoder output format changed twice" }
                        trackIndex = muxer.addTrack(codec.outputFormat)
                        muxer.start()
                        muxerStarted = true
                    }

                    else -> {
                        if (outputBufferIndex < 0) {
                            if (!endOfStream) {
                                return
                            }
                            continue
                        }

                        val outputBuffer = codec.getOutputBuffer(outputBufferIndex)
                            ?: throw IllegalStateException("encoder output buffer unavailable")

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            bufferInfo.size = 0
                        }

                        if (bufferInfo.size > 0) {
                            check(muxerStarted) { "muxer has not started" }
                            outputBuffer.position(bufferInfo.offset)
                            outputBuffer.limit(bufferInfo.offset + bufferInfo.size)
                            muxer.writeSampleData(trackIndex, outputBuffer, bufferInfo)
                        }

                        codec.releaseOutputBuffer(outputBufferIndex, false)

                        if ((bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                            return
                        }
                    }
                }
            }
        }

        private fun normalizePresentationTimeUs(presentationTimeUs: Long): Long {
            val normalized = presentationTimeUs.coerceAtLeast(lastPresentationTimeUs + 1)
            lastPresentationTimeUs = normalized
            return normalized
        }

        private fun releaseResources(): Throwable? {
            var firstError: Throwable? = null

            fun capture(block: () -> Unit) {
                runCatching(block).exceptionOrNull()?.let { error ->
                    if (firstError == null) {
                        firstError = error
                    }
                }
            }

            if (codecStarted) {
                capture { codec.stop() }
                codecStarted = false
            }
            capture { codec.release() }
            if (muxerStarted) {
                capture { muxer.stop() }
                muxerStarted = false
            }
            capture { muxer.release() }

            return firstError
        }

        private fun releaseConstructionResources(
            codec: MediaCodec?,
            codecStarted: Boolean,
            muxer: MediaMuxer?
        ) {
            if (codecStarted) {
                runCatching { codec?.stop() }
            }
            runCatching { codec?.release() }
            runCatching { muxer?.release() }
        }

        private fun selectColorFormat(codec: MediaCodec): Int {
            val supportedFormats = codec.codecInfo
                .getCapabilitiesForType(ENCODER_MIME_TYPE)
                .colorFormats
                .toList()

            return when {
                supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar
                supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                supportedFormats.contains(MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible) ->
                    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar
                else -> throw IllegalStateException("unsupported_color_format_${supportedFormats.joinToString(separator = "_")}")
            }
        }
    }

    private sealed interface FramePacket {
        data class Frame(
            val data: ByteArray,
            val presentationTimeUs: Long
        ) : FramePacket

        data object EndOfStream : FramePacket
    }

    private companion object {
        const val TAG = "MediaCodecVideoRecorder"
        const val ENCODER_MIME_TYPE = "video/avc"
        const val OUTPUT_MIME_TYPE = "video/mp4"
        const val FRAME_RATE = 15
        const val I_FRAME_INTERVAL_SECONDS = 1
        const val DEQUEUE_TIMEOUT_US = 10_000L
        const val MAX_PENDING_FRAMES = 2
        const val WORKER_JOIN_TIMEOUT_MS = 5_000L
    }
}
