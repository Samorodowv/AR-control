package com.example.ar_control.diagnostics

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.ui.preview.PreviewUiState
import com.example.ar_control.recording.RecordedClip
import com.example.ar_control.recording.RecordingStatus
import java.time.Instant
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class InMemorySessionLogTest {

    @Test
    fun record_discardsOldestEntriesBeyondCapacity() {
        val timestamps = ArrayDeque(
            listOf(
                Instant.parse("2026-04-17T10:00:00Z"),
                Instant.parse("2026-04-17T10:00:01Z"),
                Instant.parse("2026-04-17T10:00:02Z")
            )
        )
        val sessionLog = InMemorySessionLog(
            capacity = 2,
            now = { timestamps.removeFirst() }
        )

        sessionLog.record("PreviewViewModel", "first")
        sessionLog.record("PreviewViewModel", "second")
        sessionLog.record("PreviewViewModel", "third")

        assertEquals(
            listOf("second", "third"),
            sessionLog.snapshot().map(SessionLogEntry::message)
        )
    }

    @Test
    fun buildDiagnosticsReport_includesUiStateAndLogEntries() {
        val timestamps = ArrayDeque(
            listOf(
                Instant.parse("2026-04-17T10:00:00Z"),
                Instant.parse("2026-04-17T10:00:01Z")
            )
        )
        val sessionLog = InMemorySessionLog(now = { timestamps.removeFirst() })
        sessionLog.record("PreviewViewModel", "Enable camera tapped")
        sessionLog.record("AndroidUvcLibraryAdapter", "Preview start failed: open_failed")

        val report = DiagnosticsReportBuilder(
            appVersionName = "1.0",
            appVersionCode = 1,
            generatedAt = { Instant.parse("2026-04-17T10:15:00Z") }
        ).build(
            uiState = PreviewUiState(
                glassesStatus = "Glasses: available",
                cameraStatus = "Camera enabled",
                canEnableCamera = false,
                canStartPreview = true,
                canStopPreview = false,
                isPreviewRunning = false,
                previewSize = PreviewSize(width = 1920, height = 1080),
                zoomFactor = 1.15f,
                recordVideoEnabled = true,
                gemmaSubtitlesEnabled = true,
                gemmaModelDisplayName = "gemma-4-E2B-it.litertlm",
                isGemmaModelDownloadInProgress = true,
                gemmaModelDownloadProgressText = "Gemma model: downloading 14%",
                recordingStatus = RecordingStatus.Recording,
                recordedClips = listOf(
                    RecordedClip(
                        id = "clip-2",
                        filePath = "/sdcard/Movies/AR_Control/clip-2.mp4",
                        createdAtEpochMillis = 1_000L,
                        durationMillis = 2_000L,
                        width = 1920,
                        height = 1080,
                        fileSizeBytes = 10_000L,
                        mimeType = "video/mp4"
                    ),
                    RecordedClip(
                        id = "clip-1",
                        filePath = "/sdcard/Movies/AR_Control/clip-1.mp4",
                        createdAtEpochMillis = 500L,
                        durationMillis = 1_500L,
                        width = 1920,
                        height = 1080,
                        fileSizeBytes = 9_000L,
                        mimeType = "video/mp4"
                    )
                ),
                selectedClipId = "clip-2",
                errorMessage = "open_failed"
            ),
            entries = sessionLog.snapshot()
        )

        assertTrue(report.contains("AR Control diagnostics"))
        assertTrue(report.contains("Generated at: 2026-04-17T10:15:00Z"))
        assertTrue(report.contains("App version: 1.0 (1)"))
        assertTrue(report.contains("Glasses status: Glasses: available"))
        assertTrue(report.contains("Camera status: Camera enabled"))
        assertTrue(report.contains("Preview size: 1920x1080"))
        assertTrue(report.contains("Zoom factor: 1.15"))
        assertTrue(report.contains("Record video enabled: true"))
        assertTrue(report.contains("Gemma subtitles enabled: true"))
        assertTrue(report.contains("Gemma model: gemma-4-E2B-it.litertlm"))
        assertTrue(report.contains("Gemma model download in progress: true"))
        assertTrue(report.contains("Gemma model download status: Gemma model: downloading 14%"))
        assertTrue(report.contains("Recording status: Recording"))
        assertTrue(report.contains("Recorded clips: 2"))
        assertTrue(report.contains("Selected clip id: clip-2"))
        assertTrue(report.contains("Error: open_failed"))
        assertTrue(report.contains("[PreviewViewModel] Enable camera tapped"))
        assertTrue(report.contains("[AndroidUvcLibraryAdapter] Preview start failed: open_failed"))
    }
}
