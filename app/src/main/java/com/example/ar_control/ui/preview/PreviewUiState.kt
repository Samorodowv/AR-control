package com.example.ar_control.ui.preview

import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.recording.RecordedClip
import com.example.ar_control.recording.RecordingStatus
import com.example.ar_control.recovery.BrokenClipMetadata

data class PreviewUiState(
    val glassesStatus: String = "Glasses: disconnected",
    val cameraStatus: String = "Camera: idle",
    val canEnableCamera: Boolean = true,
    val canStartPreview: Boolean = false,
    val canStopPreview: Boolean = false,
    val isPreviewRunning: Boolean = false,
    val previewSize: PreviewSize? = null,
    val zoomFactor: Float = 1.0f,
    val recordVideoEnabled: Boolean = false,
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val recordedClips: List<RecordedClip> = emptyList(),
    val selectedClipId: String? = null,
    val errorMessage: String? = null,
    val isSafeMode: Boolean = false,
    val safeModeReason: String? = null,
    val brokenClipMetadata: BrokenClipMetadata? = null,
    val canChangeRecordVideo: Boolean = true
) {
    val selectedClip: RecordedClip?
        get() = recordedClips.firstOrNull { it.id == selectedClipId }

    val canOpenSelectedClip: Boolean
        get() = selectedClip != null

    val canShareSelectedClip: Boolean
        get() = selectedClip != null

    val canDeleteSelectedClip: Boolean
        get() = selectedClip != null
}
