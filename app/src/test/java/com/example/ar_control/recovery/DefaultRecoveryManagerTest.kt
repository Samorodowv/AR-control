package com.example.ar_control.recovery

import com.example.ar_control.diagnostics.InMemorySessionLog
import java.io.File
import java.nio.file.Files
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class DefaultRecoveryManagerTest {

    @Test
    fun initialize_afterRecordingCrash_enablesSafeMode_collectsClipMetadata_andDeletesFile() {
        val tempDirectory = Files.createTempDirectory("recovery-manager-recording-crash").toFile()
        val brokenClip = File(tempDirectory, "broken.mp4").apply {
            writeBytes(byteArrayOf(1, 2, 3, 4))
            setLastModified(1_700_000_000_000L)
        }
        val stateStore = InMemoryRecoveryStateStore(
            RecoveryState(
                stage = RecoveryStage.RECORDING_RUNNING,
                activeRecordingFilePath = brokenClip.absolutePath,
                brokenClipMetadata = BrokenClipMetadata(
                    filePath = brokenClip.absolutePath,
                    fileSizeBytes = 0L,
                    lastModifiedEpochMillis = null,
                    durationMillis = 12_345L,
                    width = 1920,
                    height = 1080,
                    mimeType = "video/mp4"
                )
            )
        )
        val sessionLog = InMemorySessionLog()

        val manager = DefaultRecoveryManager(
            stateStore = stateStore,
            clipMetadataReader = FailIfCalledClipMetadataReader(),
            sessionLog = sessionLog
        )

        val snapshot = manager.snapshot()

        assertTrue(snapshot.isSafeMode)
        assertEquals("Recovered after abnormal termination during recording", snapshot.safeModeReason)
        assertNotNull(snapshot.brokenClipMetadata)
        assertEquals(brokenClip.absolutePath, snapshot.brokenClipMetadata?.filePath)
        assertEquals(4L, snapshot.brokenClipMetadata?.fileSizeBytes)
        assertEquals(1_700_000_000_000L, snapshot.brokenClipMetadata?.lastModifiedEpochMillis)
        assertEquals(1920, snapshot.brokenClipMetadata?.width)
        assertEquals(1080, snapshot.brokenClipMetadata?.height)
        assertEquals("video/mp4", snapshot.brokenClipMetadata?.mimeType)
        assertFalse(brokenClip.exists())
        assertEquals(RecoveryStage.IDLE, stateStore.currentState.stage)
        assertTrue(
            sessionLog.snapshot().any { entry ->
                entry.message.contains("Deleted broken recording artifact")
            }
        )
    }

    @Test
    fun clearSafeMode_reenablesCameraTestingWithoutRemovingLoggedMetadata() {
        val metadata = BrokenClipMetadata(
            filePath = "/tmp/clip.mp4",
            fileSizeBytes = 128L,
            lastModifiedEpochMillis = 1_700_000_000_000L,
            durationMillis = 5_000L,
            width = 1280,
            height = 720,
            mimeType = "video/mp4"
        )
        val stateStore = InMemoryRecoveryStateStore(
            RecoveryState(
                stage = RecoveryStage.IDLE,
                safeModeActive = true,
                safeModeReason = "Recovered after abnormal termination during preview",
                brokenClipMetadata = metadata
            )
        )
        val manager = DefaultRecoveryManager(
            stateStore = stateStore,
            clipMetadataReader = FailIfCalledClipMetadataReader(),
            sessionLog = InMemorySessionLog()
        )

        manager.clearSafeMode()

        val snapshot = manager.snapshot()
        assertFalse(snapshot.isSafeMode)
        assertEquals(metadata, snapshot.brokenClipMetadata)
        assertFalse(stateStore.currentState.safeModeActive)
        assertEquals(RecoveryStage.IDLE, stateStore.currentState.stage)
    }

    @Test
    fun initialize_afterRecordingCrash_usesPersistedBrokenClipHintWithoutReadingMediaFile() {
        val tempDirectory = Files.createTempDirectory("recovery-manager-safe-hint").toFile()
        val brokenClip = File(tempDirectory, "broken.mp4").apply {
            writeBytes(byteArrayOf(9, 8, 7, 6, 5))
            setLastModified(1_701_000_000_000L)
        }
        val persistedHint = BrokenClipMetadata(
            filePath = brokenClip.absolutePath,
            fileSizeBytes = 0L,
            lastModifiedEpochMillis = null,
            durationMillis = null,
            width = 1920,
            height = 1080,
            mimeType = "video/mp4"
        )
        val stateStore = InMemoryRecoveryStateStore(
            RecoveryState(
                stage = RecoveryStage.RECORDING_RUNNING,
                activeRecordingFilePath = brokenClip.absolutePath,
                brokenClipMetadata = persistedHint
            )
        )

        val manager = DefaultRecoveryManager(
            stateStore = stateStore,
            clipMetadataReader = FailIfCalledClipMetadataReader(),
            sessionLog = InMemorySessionLog()
        )

        val snapshot = manager.snapshot()

        assertTrue(snapshot.isSafeMode)
        assertEquals(5L, snapshot.brokenClipMetadata?.fileSizeBytes)
        assertEquals(1_701_000_000_000L, snapshot.brokenClipMetadata?.lastModifiedEpochMillis)
        assertEquals(1920, snapshot.brokenClipMetadata?.width)
        assertEquals(1080, snapshot.brokenClipMetadata?.height)
        assertEquals("video/mp4", snapshot.brokenClipMetadata?.mimeType)
        assertFalse(brokenClip.exists())
    }
}

private class InMemoryRecoveryStateStore(
    initialState: RecoveryState = RecoveryState()
) : RecoveryStateStore {
    var currentState: RecoveryState = initialState
        private set

    override fun load(): RecoveryState = currentState

    override fun save(state: RecoveryState) {
        currentState = state
    }
}

private class FailIfCalledClipMetadataReader : ClipMetadataReader {
    override fun read(file: File): BrokenClipMetadata {
        throw AssertionError("ClipMetadataReader should not be called during startup recovery")
    }
}
