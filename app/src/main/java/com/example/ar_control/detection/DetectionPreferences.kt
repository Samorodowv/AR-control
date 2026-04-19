package com.example.ar_control.detection

interface DetectionPreferences {
    fun isObjectDetectionEnabled(): Boolean

    fun setObjectDetectionEnabled(enabled: Boolean)
}
