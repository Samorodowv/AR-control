package com.example.ar_control.face

import android.content.Context
import android.content.SharedPreferences

interface FaceRecognitionPreferences {
    fun isFaceRecognitionEnabled(): Boolean

    fun setFaceRecognitionEnabled(enabled: Boolean)
}

class SharedPreferencesFaceRecognitionPreferences internal constructor(
    private val preferences: SharedPreferences
) : FaceRecognitionPreferences {

    constructor(
        context: Context,
        fileName: String = DEFAULT_FILE_NAME
    ) : this(
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    )

    override fun isFaceRecognitionEnabled(): Boolean {
        return preferences.getBoolean(KEY_FACE_RECOGNITION_ENABLED, true)
    }

    override fun setFaceRecognitionEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_FACE_RECOGNITION_ENABLED, enabled).apply()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "face_recognition_prefs"
        const val KEY_FACE_RECOGNITION_ENABLED = "face_recognition_enabled"
    }
}

object NoOpFaceRecognitionPreferences : FaceRecognitionPreferences {
    override fun isFaceRecognitionEnabled(): Boolean = true

    override fun setFaceRecognitionEnabled(enabled: Boolean) = Unit
}
