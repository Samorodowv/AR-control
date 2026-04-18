package com.example.ar_control.recording

import android.content.Context
import android.content.SharedPreferences

class SharedPreferencesRecordingPreferences internal constructor(
    private val preferences: SharedPreferences
) : RecordingPreferences {

    constructor(
        context: Context,
        fileName: String = DEFAULT_FILE_NAME
    ) : this(
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    )

    override fun isRecordingEnabled(): Boolean {
        return preferences.getBoolean(KEY_RECORDING_ENABLED, false)
    }

    override fun setRecordingEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_RECORDING_ENABLED, enabled).apply()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "recording_prefs"
        const val KEY_RECORDING_ENABLED = "recording_enabled"
    }
}
