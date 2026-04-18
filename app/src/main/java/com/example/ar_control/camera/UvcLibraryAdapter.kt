package com.example.ar_control.camera

import com.example.ar_control.recording.RecordingInputTarget

interface UvcLibraryAdapter {
    sealed interface OpenResult {
        data class Started(val previewSize: PreviewSize) : OpenResult
        data object MissingSurface : OpenResult
        data object MissingDevice : OpenResult
        data object MissingCameraPermission : OpenResult
        data object OpenFailed : OpenResult
        data object PreviewStartFailed : OpenResult
    }

    sealed interface RecordingResult {
        data object Started : RecordingResult
        data object NotOpen : RecordingResult
        data object AlreadyRecording : RecordingResult
        data object Failed : RecordingResult
    }

    suspend fun open(surfaceToken: CameraSource.SurfaceToken): OpenResult

    suspend fun stop()
    suspend fun startRecording(target: RecordingInputTarget): RecordingResult
    suspend fun stopRecording()
}
