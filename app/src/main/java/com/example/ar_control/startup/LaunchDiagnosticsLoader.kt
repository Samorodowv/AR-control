package com.example.ar_control.startup

import com.example.ar_control.diagnostics.SessionLogEntry
import com.example.ar_control.recovery.RecoveryStage
import com.example.ar_control.recovery.RecoveryState
import java.time.Instant

data class LaunchDiagnosticsSnapshot(
    val shouldInterceptLaunch: Boolean,
    val recoveryState: RecoveryState,
    val displayMessage: String,
    val report: String
)

class LaunchDiagnosticsLoader(
    private val appVersionName: String,
    private val appVersionCode: Int,
    private val recoveryStateProvider: () -> RecoveryState,
    private val fatalCrashReportProvider: () -> String? = { null },
    private val sessionLogProvider: () -> List<SessionLogEntry>,
    private val generatedAt: () -> Instant = { Instant.now() }
) {

    fun load(): LaunchDiagnosticsSnapshot {
        val recoveryState = runCatching(recoveryStateProvider).getOrDefault(RecoveryState())
        val fatalCrashReport = runCatching(fatalCrashReportProvider).getOrNull()?.takeIf { it.isNotBlank() }
        val sessionLogEntries = runCatching(sessionLogProvider).getOrDefault(emptyList())
        val shouldInterceptLaunch =
            recoveryState.safeModeActive ||
                recoveryState.stage != RecoveryStage.IDLE ||
                fatalCrashReport != null

        return LaunchDiagnosticsSnapshot(
            shouldInterceptLaunch = shouldInterceptLaunch,
            recoveryState = recoveryState,
            displayMessage = buildDisplayMessage(recoveryState, fatalCrashReport != null),
            report = buildReport(recoveryState, fatalCrashReport, sessionLogEntries)
        )
    }

    private fun buildDisplayMessage(
        recoveryState: RecoveryState,
        hasFatalCrashEntry: Boolean
    ): String {
        return recoveryState.safeModeReason
            ?: when {
                recoveryState.stage == RecoveryStage.RECORDING_RUNNING ->
                    "The previous launch ended during video recording. Share logs before reopening the main app."
                recoveryState.stage == RecoveryStage.PREVIEW_RUNNING ->
                    "The previous launch ended during camera preview. Share logs before reopening the main app."
                hasFatalCrashEntry ->
                    "A fatal crash was recorded during the previous launch. Share logs before reopening the main app."
                else ->
                    "Crash diagnostics are available."
            }
    }

    private fun buildReport(
        recoveryState: RecoveryState,
        fatalCrashReport: String?,
        sessionLogEntries: List<SessionLogEntry>
    ): String {
        return buildString {
            appendLine("AR Control launch diagnostics")
            appendLine("Generated at: ${generatedAt()}")
            appendLine("App version: $appVersionName ($appVersionCode)")
            appendLine("Recovery stage: ${recoveryState.stage}")
            appendLine("Safe mode active: ${recoveryState.safeModeActive}")
            recoveryState.safeModeReason?.let { appendLine("Safe mode reason: $it") }
            recoveryState.activeRecordingFilePath?.let {
                appendLine("Active recording file path: $it")
            }
            recoveryState.brokenClipMetadata?.let {
                appendLine("Broken clip metadata: ${it.toSummaryString()}")
            }
            fatalCrashReport?.let {
                appendLine("Fatal crash marker:")
                appendLine(it)
            }
            appendLine()
            appendLine("Event log:")
            if (sessionLogEntries.isEmpty()) {
                appendLine("(no events recorded)")
            } else {
                sessionLogEntries.forEach { entry ->
                    appendLine("${entry.timestamp} [${entry.source}] ${entry.message}")
                }
            }
        }
    }
}
