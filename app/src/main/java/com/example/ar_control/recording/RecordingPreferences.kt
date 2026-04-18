package com.example.ar_control.recording

interface RecordingPreferences {
    fun isRecordingEnabled(): Boolean
    fun setRecordingEnabled(enabled: Boolean)
}
