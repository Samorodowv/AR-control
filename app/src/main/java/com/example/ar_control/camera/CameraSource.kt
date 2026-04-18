package com.example.ar_control.camera

import com.example.ar_control.recording.RecordingInputTarget

interface CameraSource {
    interface SurfaceToken

    sealed interface StartResult {
        data class Started(val previewSize: PreviewSize) : StartResult
        data class Failed(val reason: String) : StartResult
    }

    sealed interface RecordingStartResult {
        data object Started : RecordingStartResult
        data class Failed(val reason: String) : RecordingStartResult
    }

    suspend fun start(surfaceToken: SurfaceToken): StartResult

    suspend fun stop()
    suspend fun startRecording(target: RecordingInputTarget): RecordingStartResult
    suspend fun stopRecording()
}
