package com.example.ar_control.recording

import com.example.ar_control.camera.PreviewSize

interface VideoRecorder {
    sealed interface StartResult {
        data class Started(
            val inputTarget: RecordingInputTarget,
            val outputFilePath: String,
            val startedAtEpochMillis: Long
        ) : StartResult

        data class Failed(val reason: String) : StartResult
    }

    sealed interface StopResult {
        data class Finished(val clip: RecordedClip) : StopResult
        data class Failed(val reason: String) : StopResult
    }

    sealed interface CancelResult {
        data object Cancelled : CancelResult
        data class Failed(val reason: String) : CancelResult
    }

    suspend fun start(previewSize: PreviewSize): StartResult

    suspend fun stop(): StopResult

    suspend fun cancel(): CancelResult
}
