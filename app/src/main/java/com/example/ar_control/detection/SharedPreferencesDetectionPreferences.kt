package com.example.ar_control.detection

import android.content.Context
import android.content.SharedPreferences

class SharedPreferencesDetectionPreferences internal constructor(
    private val preferences: SharedPreferences
) : DetectionPreferences {

    constructor(
        context: Context,
        fileName: String = DEFAULT_FILE_NAME
    ) : this(
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    )

    override fun isObjectDetectionEnabled(): Boolean {
        return preferences.getBoolean(KEY_OBJECT_DETECTION_ENABLED, false)
    }

    override fun setObjectDetectionEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_OBJECT_DETECTION_ENABLED, enabled).apply()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "detection_prefs"
        const val KEY_OBJECT_DETECTION_ENABLED = "object_detection_enabled"
    }
}
