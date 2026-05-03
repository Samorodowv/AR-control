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
        assertEquals(FaceAccessStatus.APPROVED, face.accessStatus)
    }

    @Test
    fun remember_persistsRequestedAccessStatus() {
        val preferencesName = "face_store_status_test_${System.nanoTime()}"
        val store = SharedPreferencesFaceEmbeddingStore(context, preferencesName)

        val face = store.remember(
            FaceEmbedding(floatArrayOf(1f, 0.5f, 0f)),
            FaceAccessStatus.BANNED
        )
        val reloaded = SharedPreferencesFaceEmbeddingStore(context, preferencesName)

        assertEquals(FaceAccessStatus.BANNED, face.accessStatus)
        assertEquals(listOf(face), reloaded.loadAll())
    }

    @Test
    fun remember_updatesExistingFaceStatusInsteadOfDuplicating() {
        val preferencesName = "face_store_update_test_${System.nanoTime()}"
        val store = SharedPreferencesFaceEmbeddingStore(context, preferencesName)
        val bannedFace = store.remember(
            FaceEmbedding(floatArrayOf(1f, 0.5f, 0f)),
            FaceAccessStatus.BANNED
        )

        val approvedFace = store.remember(
            FaceEmbedding(floatArrayOf(1f, 0.5f, 0f)),
            FaceAccessStatus.APPROVED,
            existingFaceId = bannedFace.id
        )

        assertEquals(bannedFace.id, approvedFace.id)
        assertEquals(FaceAccessStatus.APPROVED, approvedFace.accessStatus)
        assertEquals(
            listOf(approvedFace),
            SharedPreferencesFaceEmbeddingStore(context, preferencesName).loadAll()
        )
    }

    @Test
    fun loadAll_defaultsLegacyFacesToApproved() {
        val preferencesName = "face_store_legacy_test_${System.nanoTime()}"
        context.getSharedPreferences(preferencesName, Context.MODE_PRIVATE)
            .edit()
            .putString(
                "faces",
                """[{"id":"face-legacy","label":"Legacy","embedding":[1.0,0.5]}]"""
            )
            .apply()

        val faces = SharedPreferencesFaceEmbeddingStore(context, preferencesName).loadAll()

        assertEquals(FaceAccessStatus.APPROVED, faces.single().accessStatus)
    }

    @Test
    fun clear_removesRememberedFaces() {
        val preferencesName = "face_store_clear_test_${System.nanoTime()}"
        val store = SharedPreferencesFaceEmbeddingStore(context, preferencesName)
        store.remember(FaceEmbedding(floatArrayOf(1f, 0.5f, 0f)), FaceAccessStatus.BANNED)

        store.clear()

        assertEquals(
            emptyList<RememberedFace>(),
            SharedPreferencesFaceEmbeddingStore(context, preferencesName).loadAll()
        )
    }

    @Test
    fun replaceAll_persistsReplacementList() {
        val preferencesName = "face_store_replace_test_${System.nanoTime()}"
        val store = SharedPreferencesFaceEmbeddingStore(context, preferencesName)
        store.remember(FaceEmbedding(floatArrayOf(1f, 0f)), FaceAccessStatus.BANNED)
        val replacement = RememberedFace(
            id = "face-custom",
            label = "Nikolay",
            embedding = FaceEmbedding(floatArrayOf(0.25f, 0.75f)),
            accessStatus = FaceAccessStatus.APPROVED
        )

        store.replaceAll(listOf(replacement))

        assertEquals(
            listOf(replacement),
            SharedPreferencesFaceEmbeddingStore(context, preferencesName).loadAll()
        )
    }
}
