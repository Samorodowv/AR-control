package com.example.ar_control.recording

sealed interface RecordingStatus {
    data object Idle : RecordingStatus
    data object Starting : RecordingStatus
    data object Recording : RecordingStatus
    data object Finalizing : RecordingStatus
    data class Failed(val reason: String) : RecordingStatus
}
