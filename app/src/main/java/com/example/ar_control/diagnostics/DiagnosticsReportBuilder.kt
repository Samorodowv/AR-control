package com.example.ar_control.diagnostics

import com.example.ar_control.ui.preview.PreviewUiState
import java.time.Instant
import java.util.Locale

class DiagnosticsReportBuilder(
    private val appVersionName: String,
    private val appVersionCode: Int,
    private val generatedAt: () -> Instant = { Instant.now() }
) {

    fun build(
        uiState: PreviewUiState,
        entries: List<SessionLogEntry>
    ): String {
        return buildString {
            appendLine("AR Control diagnostics")
            appendLine("Generated at: ${generatedAt()}")
            appendLine("App version: $appVersionName ($appVersionCode)")
            appendLine("Glasses status: ${uiState.glassesStatus}")
            appendLine("Camera status: ${uiState.cameraStatus}")
            appendLine("Preview running: ${uiState.isPreviewRunning}")
            uiState.previewSize?.let { appendLine("Preview size: ${it.width}x${it.height}") }
            appendLine("Zoom factor: ${String.format(Locale.US, "%.2f", uiState.zoomFactor)}")
            appendLine("Record video enabled: ${uiState.recordVideoEnabled}")
            appendLine("Inference FPS: ${String.format(Locale.US, "%.2f", uiState.inferenceFps)}")
            appendLine("Inference backend: ${uiState.inferenceBackendLabel ?: "none"}")
            appendLine("Recording status: ${uiState.recordingStatus}")
            appendLine("Safe mode: ${uiState.isSafeMode}")
            uiState.safeModeReason?.let { appendLine("Safe mode reason: $it") }
            uiState.brokenClipMetadata?.let {
                appendLine("Broken clip metadata: ${it.toSummaryString()}")
            }
            appendLine("Recorded clips: ${uiState.recordedClips.size}")
            appendLine("Selected clip id: ${uiState.selectedClipId ?: "none"}")
            appendLine("Can enable camera: ${uiState.canEnableCamera}")
            appendLine("Can start preview: ${uiState.canStartPreview}")
            appendLine("Can stop preview: ${uiState.canStopPreview}")
            appendLine("Can change record video: ${uiState.canChangeRecordVideo}")
            uiState.errorMessage?.let { appendLine("Error: $it") }
            appendLine()
            appendLine("Event log:")
            if (entries.isEmpty()) {
                appendLine("(no events recorded)")
            } else {
                entries.forEach { entry ->
                    appendLine("${entry.timestamp} [${entry.source}] ${entry.message}")
                }
            }
        }
    }
}
