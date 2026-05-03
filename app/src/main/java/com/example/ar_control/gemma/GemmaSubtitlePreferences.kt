package com.example.ar_control.gemma

const val DEFAULT_GEMMA_CAPTION_PROMPT =
    "Опиши подробно на русском языке. " +
        "Ответь только по-русски, без английских слов. " +
        "2-3 предложения, до 60 слов. Не упоминай, что это изображение."

interface GemmaSubtitlePreferences {
    fun isGemmaSubtitlesEnabled(): Boolean

    fun setGemmaSubtitlesEnabled(enabled: Boolean)

    fun getCaptionPrompt(): String

    fun setCaptionPrompt(prompt: String)

    fun getModelPath(): String?

    fun getModelDisplayName(): String?

    fun setModel(path: String, displayName: String?)

    fun clearModel()
}
