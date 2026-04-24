package com.example.ar_control.gemma

interface GemmaSubtitlePreferences {
    fun isGemmaSubtitlesEnabled(): Boolean

    fun setGemmaSubtitlesEnabled(enabled: Boolean)

    fun getModelPath(): String?

    fun getModelDisplayName(): String?

    fun setModel(path: String, displayName: String?)

    fun clearModel()
}
