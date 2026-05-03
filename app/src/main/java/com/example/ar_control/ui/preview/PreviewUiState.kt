package com.example.ar_control.ui.preview

import com.example.ar_control.camera.CameraSourceKind
import com.example.ar_control.camera.PreviewSize
import com.example.ar_control.detection.DetectedObject
import com.example.ar_control.face.FaceBoundingBox
import com.example.ar_control.gemma.DEFAULT_GEMMA_CAPTION_PROMPT
import com.example.ar_control.recording.RecordedClip
import com.example.ar_control.recording.RecordingStatus
import com.example.ar_control.recovery.BrokenClipMetadata

data class PreviewUiState(
    val selectedCameraSource: CameraSourceKind = CameraSourceKind.XREAL,
    val glassesStatus: String = "Очки: отключены",
    val cameraStatus: String = "Камера: ожидание",
    val canEnableCamera: Boolean = true,
    val canStartPreview: Boolean = false,
    val canStopPreview: Boolean = false,
    val isPreviewRunning: Boolean = false,
    val previewSize: PreviewSize? = null,
    val zoomFactor: Float = 1.0f,
    val recordVideoEnabled: Boolean = false,
    val objectDetectionEnabled: Boolean = false,
    val faceRecognitionEnabled: Boolean = true,
    val transparentHudEnabled: Boolean = false,
    val gemmaSubtitlesEnabled: Boolean = false,
    val gemmaModelDisplayName: String? = null,
    val isGemmaModelDownloadInProgress: Boolean = false,
    val gemmaModelDownloadProgressText: String? = null,
    val gemmaPrompt: String = DEFAULT_GEMMA_CAPTION_PROMPT,
    val gemmaSubtitleText: String = "",
    val faceRecognitionStatusText: String? = null,
    val recordingStatus: RecordingStatus = RecordingStatus.Idle,
    val detectedObjects: List<DetectedObject> = emptyList(),
    val faceBoxes: List<FaceBoundingBox> = emptyList(),
    val inferenceFps: Float = 0f,
    val inferenceBackendLabel: String? = null,
    val recordedClips: List<RecordedClip> = emptyList(),
    val selectedClipId: String? = null,
    val errorMessage: String? = null,
    val isSafeMode: Boolean = false,
    val safeModeReason: String? = null,
    val brokenClipMetadata: BrokenClipMetadata? = null,
    val canChangeRecordVideo: Boolean = true,
    val canChangeObjectDetection: Boolean = true,
    val canChangeFaceRecognition: Boolean = true,
    val canChangeTransparentHud: Boolean = true,
    val canChangeGemmaSubtitles: Boolean = true,
    val canChangeCameraSource: Boolean = true
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
