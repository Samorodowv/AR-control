package com.example.ar_control.gemma

import android.content.Context
import android.content.SharedPreferences

class SharedPreferencesGemmaSubtitlePreferences internal constructor(
    private val preferences: SharedPreferences
) : GemmaSubtitlePreferences {

    constructor(
        context: Context,
        fileName: String = DEFAULT_FILE_NAME
    ) : this(
        context.applicationContext.getSharedPreferences(fileName, Context.MODE_PRIVATE)
    )

    override fun isGemmaSubtitlesEnabled(): Boolean {
        return preferences.getBoolean(KEY_ENABLED, false)
    }

    override fun setGemmaSubtitlesEnabled(enabled: Boolean) {
        preferences.edit().putBoolean(KEY_ENABLED, enabled).apply()
    }

    override fun getCaptionPrompt(): String {
        val prompt = preferences.getString(KEY_CAPTION_PROMPT, null)
            ?.takeIf { storedPrompt -> storedPrompt.isNotBlank() }
            ?: return DEFAULT_GEMMA_CAPTION_PROMPT
        return if (prompt == OLD_DEFAULT_GEMMA_CAPTION_PROMPT) {
            DEFAULT_GEMMA_CAPTION_PROMPT
        } else {
            prompt
        }
    }

    override fun setCaptionPrompt(prompt: String) {
        preferences.edit()
            .putString(KEY_CAPTION_PROMPT, prompt)
            .apply()
    }

    override fun getModelPath(): String? {
        return preferences.getString(KEY_MODEL_PATH, null)
    }

    override fun getModelDisplayName(): String? {
        return preferences.getString(KEY_MODEL_DISPLAY_NAME, null)
    }

    override fun setModel(path: String, displayName: String?) {
        preferences.edit()
            .putString(KEY_MODEL_PATH, path)
            .putString(KEY_MODEL_DISPLAY_NAME, displayName)
            .apply()
    }

    override fun clearModel() {
        preferences.edit()
            .remove(KEY_MODEL_PATH)
            .remove(KEY_MODEL_DISPLAY_NAME)
            .apply()
    }

    private companion object {
        const val DEFAULT_FILE_NAME = "gemma_subtitle_prefs"
        const val KEY_ENABLED = "gemma_subtitles_enabled"
        const val KEY_CAPTION_PROMPT = "gemma_caption_prompt"
        const val KEY_MODEL_PATH = "gemma_model_path"
        const val KEY_MODEL_DISPLAY_NAME = "gemma_model_display_name"
        const val OLD_DEFAULT_GEMMA_CAPTION_PROMPT =
            "Опиши на русском языке. " +
                "Ответь только по-русски, без английских слов. " +
                "До 20 слов. Не упоминай, что это изображение."
    }
}
