package com.example.ar_control.gemma

import android.content.Context
import com.example.ar_control.ArControlApp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class SharedPreferencesGemmaSubtitlePreferencesTest {

    private lateinit var context: Context
    private lateinit var preferences: SharedPreferencesGemmaSubtitlePreferences

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
        context.getSharedPreferences(FILE_NAME, Context.MODE_PRIVATE).edit().clear().commit()
        preferences = SharedPreferencesGemmaSubtitlePreferences(context, FILE_NAME)
    }

    @Test
    fun defaultsToDisabledWithoutModel() {
        assertFalse(preferences.isGemmaSubtitlesEnabled())
        assertNull(preferences.getModelPath())
        assertNull(preferences.getModelDisplayName())
    }

    @Test
    fun persistsEnabledState() {
        preferences.setGemmaSubtitlesEnabled(true)

        val reloaded = SharedPreferencesGemmaSubtitlePreferences(context, FILE_NAME)

        assertTrue(reloaded.isGemmaSubtitlesEnabled())
    }

    @Test
    fun persistsImportedModelMetadata() {
        preferences.setModel(path = "/private/models/gemma.litertlm", displayName = "gemma.litertlm")

        val reloaded = SharedPreferencesGemmaSubtitlePreferences(context, FILE_NAME)

        assertEquals("/private/models/gemma.litertlm", reloaded.getModelPath())
        assertEquals("gemma.litertlm", reloaded.getModelDisplayName())
    }

    @Test
    fun clearModelRemovesPathAndDisplayName() {
        preferences.setModel(path = "/private/models/gemma.litertlm", displayName = "gemma.litertlm")

        preferences.clearModel()

        assertNull(preferences.getModelPath())
        assertNull(preferences.getModelDisplayName())
    }

    private companion object {
        const val FILE_NAME = "gemma_subtitle_prefs_test"
    }
}
