package com.example.ar_control.camera

import android.content.Context

enum class CameraSourceKind {
    XREAL,
    ANDROID
}

interface CameraSourcePreferences {
    fun getSelectedCameraSource(): CameraSourceKind

    fun setSelectedCameraSource(source: CameraSourceKind)
}

class SharedPreferencesCameraSourcePreferences internal constructor(
    context: Context,
    fileName: String = FILE_NAME
) : CameraSourcePreferences {
    private val preferences = context.applicationContext.getSharedPreferences(
        fileName,
        Context.MODE_PRIVATE
    )

    override fun getSelectedCameraSource(): CameraSourceKind {
        val rawValue = preferences.getString(KEY_SELECTED_CAMERA_SOURCE, null)
        return rawValue?.let(::parseCameraSourceKind) ?: CameraSourceKind.XREAL
    }

    override fun setSelectedCameraSource(source: CameraSourceKind) {
        preferences.edit()
            .putString(KEY_SELECTED_CAMERA_SOURCE, source.name)
            .apply()
    }

    private fun parseCameraSourceKind(value: String): CameraSourceKind? {
        return runCatching { CameraSourceKind.valueOf(value) }.getOrNull()
    }

    private companion object {
        const val FILE_NAME = "camera_source_preferences"
        const val KEY_SELECTED_CAMERA_SOURCE = "selected_camera_source"
    }
}
