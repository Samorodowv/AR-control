package com.example.ar_control.startup

import com.example.ar_control.diagnostics.SessionLogEntry
import com.example.ar_control.recovery.BrokenClipMetadata
import com.example.ar_control.recovery.RecoveryStage
import com.example.ar_control.recovery.RecoveryState
import java.time.Instant
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LaunchDiagnosticsLoaderTest {

    @Test
    fun load_withAbnormalRecoveryState_interceptsLaunchAndIncludesLogs() {
        val loader = LaunchDiagnosticsLoader(
            appVersionName = "1.0",
            appVersionCode = 1,
            recoveryStateProvider = {
                RecoveryState(
                    stage = RecoveryStage.RECORDING_RUNNING,
                    safeModeActive = false,
                    safeModeReason = "Recovered after abnormal termination during recording",
                    brokenClipMetadata = BrokenClipMetadata(
                        filePath = "/clips/broken.mp4",
                        fileSizeBytes = 55L,
                        lastModifiedEpochMillis = 1_700_000_000_000L,
                        durationMillis = null,
                        width = 1920,
                        height = 1080,
                        mimeType = "video/mp4"
                    )
                )
            },
            fatalCrashReportProvider = { null },
            sessionLogProvider = {
                listOf(
                    SessionLogEntry(
                        timestamp = Instant.parse("2026-04-18T10:00:00Z"),
                        source = "PreviewViewModel",
                        message = "Recording started successfully"
                    )
                )
            },
            generatedAt = { Instant.parse("2026-04-18T11:00:00Z") }
        )

        val snapshot = loader.load()

        assertTrue(snapshot.shouldInterceptLaunch)
        assertTrue(snapshot.report.contains("Recovered after abnormal termination during recording"))
        assertTrue(snapshot.report.contains("Broken clip metadata: path=/clips/broken.mp4"))
        assertTrue(snapshot.report.contains("[PreviewViewModel] Recording started successfully"))
    }

    @Test
    fun load_withoutRecoveryOrFatalCrash_allowsDirectLaunch() {
        val loader = LaunchDiagnosticsLoader(
            appVersionName = "1.0",
            appVersionCode = 1,
            recoveryStateProvider = { RecoveryState() },
            fatalCrashReportProvider = { null },
            sessionLogProvider = {
                listOf(
                    SessionLogEntry(
                        timestamp = Instant.parse("2026-04-18T10:00:00Z"),
                        source = "ArControlApp",
                        message = "Application created"
                    )
                )
            },
            generatedAt = { Instant.parse("2026-04-18T11:00:00Z") }
        )

        val snapshot = loader.load()

        assertFalse(snapshot.shouldInterceptLaunch)
    }

    @Test
    fun load_withFatalCrashMarker_interceptsLaunchAndIncludesFatalSection() {
        val loader = LaunchDiagnosticsLoader(
            appVersionName = "1.0",
            appVersionCode = 1,
            recoveryStateProvider = { RecoveryState() },
            fatalCrashReportProvider = { "Thread=main\njava.lang.IllegalStateException: boom" },
            sessionLogProvider = { emptyList() },
            generatedAt = { Instant.parse("2026-04-18T11:00:00Z") }
        )

        val snapshot = loader.load()

        assertTrue(snapshot.shouldInterceptLaunch)
        assertTrue(snapshot.report.contains("Fatal crash marker:"))
        assertTrue(snapshot.report.contains("java.lang.IllegalStateException: boom"))
    }
}
