package com.example.ar_control.face

import android.content.Context
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RuntimeEnvironment
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class SharedPreferencesFaceEmbeddingStoreTest {

    private val context: Context = RuntimeEnvironment.getApplication()

    @Test
    fun remember_persistsFacesAcrossStoreInstances() {
        val preferencesName = "face_store_test_${System.nanoTime()}"
        val store = SharedPreferencesFaceEmbeddingStore(context, preferencesName)

        val face = store.remember(FaceEmbedding(floatArrayOf(1f, 0.5f, 0f)))
        val reloaded = SharedPreferencesFaceEmbeddingStore(context, preferencesName)

        assertEquals(listOf(face), reloaded.loadAll())
    }

    @Test
    fun replaceAll_persistsReplacementList() {
        val preferencesName = "face_store_replace_test_${System.nanoTime()}"
        val store = SharedPreferencesFaceEmbeddingStore(context, preferencesName)
        store.remember(FaceEmbedding(floatArrayOf(1f, 0f)))
        val replacement = RememberedFace(
            id = "face-custom",
            label = "Nikolay",
            embedding = FaceEmbedding(floatArrayOf(0.25f, 0.75f))
        )

        store.replaceAll(listOf(replacement))

        assertEquals(
            listOf(replacement),
            SharedPreferencesFaceEmbeddingStore(context, preferencesName).loadAll()
        )
    }
}
