package com.example.ar_control.recovery

import android.media.MediaMetadataRetriever
import com.example.ar_control.diagnostics.NoOpSessionLog
import com.example.ar_control.diagnostics.SessionLog
import java.io.File
import java.io.FileInputStream
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.Properties

enum class RecoveryStage {
    IDLE,
    PREVIEW_RUNNING,
    RECORDING_RUNNING
}

data class BrokenClipMetadata(
    val filePath: String,
    val fileSizeBytes: Long,
    val lastModifiedEpochMillis: Long?,
    val durationMillis: Long?,
    val width: Int?,
    val height: Int?,
    val mimeType: String?
) {
    fun toSummaryString(): String {
        return buildString {
            append("path=")
            append(filePath)
            append(", sizeBytes=")
            append(fileSizeBytes)
            lastModifiedEpochMillis?.let {
                append(", lastModifiedEpochMillis=")
                append(it)
            }
            durationMillis?.let {
                append(", durationMillis=")
                append(it)
            }
            if (width != null && height != null) {
                append(", dimensions=")
                append(width)
                append('x')
                append(height)
            }
            mimeType?.let {
                append(", mime/container=")
                append(it)
            }
        }
    }
}

data class RecoverySnapshot(
    val isSafeMode: Boolean = false,
    val safeModeReason: String? = null,
    val brokenClipMetadata: BrokenClipMetadata? = null
)

data class RecoveryState(
    val stage: RecoveryStage = RecoveryStage.IDLE,
    val activeRecordingFilePath: String? = null,
    val safeModeActive: Boolean = false,
    val safeModeReason: String? = null,
    val brokenClipMetadata: BrokenClipMetadata? = null
)

interface RecoveryStateStore {
    fun load(): RecoveryState

    fun save(state: RecoveryState)
}

interface ClipMetadataReader {
    fun read(file: File): BrokenClipMetadata
}

interface RecoveryManager {
    fun snapshot(): RecoverySnapshot

    fun markPreviewStarted()

    fun markRecordingStarted(
        outputFilePath: String,
        width: Int? = null,
        height: Int? = null,
        mimeType: String? = null
    )

    fun markCameraIdle()

    fun clearSafeMode()
}

class DefaultRecoveryManager(
    private val stateStore: RecoveryStateStore,
    private val clipMetadataReader: ClipMetadataReader,
    private val sessionLog: SessionLog = NoOpSessionLog
) : RecoveryManager {

    private val lock = Any()
    private var state: RecoveryState = initialize()

    override fun snapshot(): RecoverySnapshot = synchronized(lock) {
        state.toSnapshot()
    }

    override fun markPreviewStarted() {
        synchronized(lock) {
            state = state.copy(
                stage = RecoveryStage.PREVIEW_RUNNING,
                activeRecordingFilePath = null
            )
            stateStore.save(state)
        }
    }

    override fun markRecordingStarted(
        outputFilePath: String,
        width: Int?,
        height: Int?,
        mimeType: String?
    ) {
        synchronized(lock) {
            state = state.copy(
                stage = RecoveryStage.RECORDING_RUNNING,
                activeRecordingFilePath = outputFilePath,
                brokenClipMetadata = BrokenClipMetadata(
                    filePath = outputFilePath,
                    fileSizeBytes = 0L,
                    lastModifiedEpochMillis = null,
                    durationMillis = null,
                    width = width,
                    height = height,
                    mimeType = mimeType
                )
            )
            stateStore.save(state)
        }
    }

    override fun markCameraIdle() {
        synchronized(lock) {
            state = state.copy(
                stage = RecoveryStage.IDLE,
                activeRecordingFilePath = null
            )
            stateStore.save(state)
        }
    }

    override fun clearSafeMode() {
        synchronized(lock) {
            state = state.copy(
                stage = RecoveryStage.IDLE,
                activeRecordingFilePath = null,
                safeModeActive = false,
                safeModeReason = null
            )
            stateStore.save(state)
            sessionLog.record("RecoveryManager", "Safe mode cleared by user confirmation")
        }
    }

    private fun initialize(): RecoveryState {
        val loadedState = runCatching { stateStore.load() }.getOrDefault(RecoveryState())
        return when (loadedState.stage) {
            RecoveryStage.IDLE -> {
                if (loadedState.safeModeActive) {
                    sessionLog.record(
                        "RecoveryManager",
                        "Launching in persisted safe mode: ${loadedState.safeModeReason ?: "unknown_reason"}"
                    )
                }
                loadedState
            }

            RecoveryStage.PREVIEW_RUNNING -> {
                val recovered = loadedState.copy(
                    stage = RecoveryStage.IDLE,
                    activeRecordingFilePath = null,
                    safeModeActive = true,
                    safeModeReason = PREVIEW_SAFE_MODE_REASON
                )
                stateStore.save(recovered)
                sessionLog.record(
                    "RecoveryManager",
                    "Recovered after abnormal termination during preview"
                )
                recovered
            }

            RecoveryStage.RECORDING_RUNNING -> {
                val brokenClipMetadata = inspectAndDeleteBrokenRecording(
                    activeRecordingFilePath = loadedState.activeRecordingFilePath,
                    metadataHint = loadedState.brokenClipMetadata
                )
                val recovered = loadedState.copy(
                    stage = RecoveryStage.IDLE,
                    activeRecordingFilePath = null,
                    safeModeActive = true,
                    safeModeReason = RECORDING_SAFE_MODE_REASON,
                    brokenClipMetadata = brokenClipMetadata ?: loadedState.brokenClipMetadata
                )
                stateStore.save(recovered)
                sessionLog.record(
                    "RecoveryManager",
                    "Recovered after abnormal termination during recording"
                )
                recovered
            }
        }
    }

    private fun inspectAndDeleteBrokenRecording(
        activeRecordingFilePath: String?,
        metadataHint: BrokenClipMetadata?
    ): BrokenClipMetadata? {
        if (activeRecordingFilePath.isNullOrBlank()) {
            sessionLog.record(
                "RecoveryManager",
                "Recording crash detected without an active recording file path"
            )
            return metadataHint
        }

        val clipFile = File(activeRecordingFilePath)
        val fileSizeBytes = clipFile.takeIf(File::exists)?.length() ?: 0L
        val lastModifiedEpochMillis = clipFile.takeIf(File::exists)?.lastModified()?.takeIf { it > 0L }
        val metadata = metadataHint?.copy(
            filePath = clipFile.absolutePath,
            fileSizeBytes = fileSizeBytes,
            lastModifiedEpochMillis = lastModifiedEpochMillis
        ) ?: BrokenClipMetadata(
            filePath = clipFile.absolutePath,
            fileSizeBytes = fileSizeBytes,
            lastModifiedEpochMillis = lastModifiedEpochMillis,
            durationMillis = null,
            width = null,
            height = null,
            mimeType = null
        )

        sessionLog.record(
            "RecoveryManager",
            "Broken recording metadata: ${metadata.toSummaryString()}"
        )
        val deleted = runCatching { !clipFile.exists() || clipFile.delete() }.getOrDefault(false)
        sessionLog.record(
            "RecoveryManager",
            if (deleted) {
                "Deleted broken recording artifact at ${clipFile.absolutePath}"
            } else {
                "Failed to delete broken recording artifact at ${clipFile.absolutePath}"
            }
        )
        return metadata
    }

    private fun RecoveryState.toSnapshot(): RecoverySnapshot {
        return RecoverySnapshot(
            isSafeMode = safeModeActive,
            safeModeReason = safeModeReason,
            brokenClipMetadata = brokenClipMetadata
        )
    }

    private companion object {
        const val PREVIEW_SAFE_MODE_REASON = "Recovered after abnormal termination during preview"
        const val RECORDING_SAFE_MODE_REASON = "Recovered after abnormal termination during recording"
    }
}

class FileRecoveryStateStore(
    private val file: File
) : RecoveryStateStore {

    override fun load(): RecoveryState {
        if (!file.exists()) {
            return RecoveryState()
        }
        val properties = Properties()
        FileInputStream(file).use { input ->
            properties.load(input)
        }
        return RecoveryState(
            stage = properties.getProperty(KEY_STAGE)
                ?.let { runCatching { RecoveryStage.valueOf(it) }.getOrNull() }
                ?: RecoveryStage.IDLE,
            activeRecordingFilePath = properties.getProperty(KEY_ACTIVE_RECORDING_FILE_PATH)
                ?.takeIf(String::isNotBlank),
            safeModeActive = properties.getProperty(KEY_SAFE_MODE_ACTIVE)?.toBoolean() ?: false,
            safeModeReason = properties.getProperty(KEY_SAFE_MODE_REASON)?.takeIf(String::isNotBlank),
            brokenClipMetadata = properties.toBrokenClipMetadata()
        )
    }

    override fun save(state: RecoveryState) {
        val properties = Properties().apply {
            setProperty(KEY_STAGE, state.stage.name)
            state.activeRecordingFilePath?.let {
                setProperty(KEY_ACTIVE_RECORDING_FILE_PATH, it)
            }
            setProperty(KEY_SAFE_MODE_ACTIVE, state.safeModeActive.toString())
            state.safeModeReason?.let {
                setProperty(KEY_SAFE_MODE_REASON, it)
            }
            state.brokenClipMetadata?.let { metadata ->
                putBrokenClipMetadata(metadata)
            }
        }

        val targetPath = file.toPath()
        val directory = targetPath.parent ?: targetPath.toAbsolutePath().parent ?: return
        Files.createDirectories(directory)
        val tempFile = Files.createTempFile(directory, targetPath.fileName.toString(), ".tmp")
        try {
            tempFile.toFile().outputStream().use { output ->
                properties.store(output, null)
            }
            try {
                Files.move(
                    tempFile,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun Properties.putBrokenClipMetadata(metadata: BrokenClipMetadata) {
        setProperty(KEY_BROKEN_CLIP_FILE_PATH, metadata.filePath)
        setProperty(KEY_BROKEN_CLIP_FILE_SIZE_BYTES, metadata.fileSizeBytes.toString())
        metadata.lastModifiedEpochMillis?.let {
            setProperty(KEY_BROKEN_CLIP_LAST_MODIFIED_EPOCH_MILLIS, it.toString())
        }
        metadata.durationMillis?.let {
            setProperty(KEY_BROKEN_CLIP_DURATION_MILLIS, it.toString())
        }
        metadata.width?.let {
            setProperty(KEY_BROKEN_CLIP_WIDTH, it.toString())
        }
        metadata.height?.let {
            setProperty(KEY_BROKEN_CLIP_HEIGHT, it.toString())
        }
        metadata.mimeType?.let {
            setProperty(KEY_BROKEN_CLIP_MIME_TYPE, it)
        }
    }

    private fun Properties.toBrokenClipMetadata(): BrokenClipMetadata? {
        val filePath = getProperty(KEY_BROKEN_CLIP_FILE_PATH)?.takeIf(String::isNotBlank) ?: return null
        return BrokenClipMetadata(
            filePath = filePath,
            fileSizeBytes = getProperty(KEY_BROKEN_CLIP_FILE_SIZE_BYTES)?.toLongOrNull() ?: 0L,
            lastModifiedEpochMillis = getProperty(KEY_BROKEN_CLIP_LAST_MODIFIED_EPOCH_MILLIS)?.toLongOrNull(),
            durationMillis = getProperty(KEY_BROKEN_CLIP_DURATION_MILLIS)?.toLongOrNull(),
            width = getProperty(KEY_BROKEN_CLIP_WIDTH)?.toIntOrNull(),
            height = getProperty(KEY_BROKEN_CLIP_HEIGHT)?.toIntOrNull(),
            mimeType = getProperty(KEY_BROKEN_CLIP_MIME_TYPE)?.takeIf(String::isNotBlank)
        )
    }

    private companion object {
        const val KEY_STAGE = "stage"
        const val KEY_ACTIVE_RECORDING_FILE_PATH = "activeRecordingFilePath"
        const val KEY_SAFE_MODE_ACTIVE = "safeModeActive"
        const val KEY_SAFE_MODE_REASON = "safeModeReason"
        const val KEY_BROKEN_CLIP_FILE_PATH = "brokenClip.filePath"
        const val KEY_BROKEN_CLIP_FILE_SIZE_BYTES = "brokenClip.fileSizeBytes"
        const val KEY_BROKEN_CLIP_LAST_MODIFIED_EPOCH_MILLIS = "brokenClip.lastModifiedEpochMillis"
        const val KEY_BROKEN_CLIP_DURATION_MILLIS = "brokenClip.durationMillis"
        const val KEY_BROKEN_CLIP_WIDTH = "brokenClip.width"
        const val KEY_BROKEN_CLIP_HEIGHT = "brokenClip.height"
        const val KEY_BROKEN_CLIP_MIME_TYPE = "brokenClip.mimeType"
    }
}

class AndroidClipMetadataReader : ClipMetadataReader {
    override fun read(file: File): BrokenClipMetadata {
        var durationMillis: Long? = null
        var width: Int? = null
        var height: Int? = null
        var mimeType: String? = null
        val retriever = MediaMetadataRetriever()
        try {
            if (file.exists()) {
                retriever.setDataSource(file.absolutePath)
                durationMillis = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull()
                width = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull()
                height = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull()
                mimeType = retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_MIMETYPE)
            }
        } finally {
            runCatching { retriever.release() }
        }

        return BrokenClipMetadata(
            filePath = file.absolutePath,
            fileSizeBytes = file.takeIf(File::exists)?.length() ?: 0L,
            lastModifiedEpochMillis = file.takeIf(File::exists)?.lastModified()?.takeIf { it > 0L },
            durationMillis = durationMillis,
            width = width,
            height = height,
            mimeType = mimeType
        )
    }
}
