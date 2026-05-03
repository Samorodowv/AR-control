package com.example.ar_control.face

import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryFaceEmbeddingStoreTest {

    @Test
    fun rememberFace_appendsFacesWithStableLabels() {
        val store = InMemoryFaceEmbeddingStore()

        val first = store.remember(FaceEmbedding(floatArrayOf(1f, 0f)), FaceAccessStatus.BANNED)
        val second = store.remember(FaceEmbedding(floatArrayOf(0f, 1f)), FaceAccessStatus.APPROVED)

        assertEquals("Face 1", first.label)
        assertEquals("Face 2", second.label)
        assertEquals(FaceAccessStatus.BANNED, first.accessStatus)
        assertEquals(FaceAccessStatus.APPROVED, second.accessStatus)
        assertEquals(listOf(first, second), store.loadAll())
    }

    @Test
    fun replaceAll_overwritesRememberedFaces() {
        val store = InMemoryFaceEmbeddingStore()
        store.remember(FaceEmbedding(floatArrayOf(1f, 0f)), FaceAccessStatus.BANNED)
        val replacement = RememberedFace(
            id = "manual-id",
            label = "Nikolay",
            embedding = FaceEmbedding(floatArrayOf(0.5f, 0.5f)),
            accessStatus = FaceAccessStatus.APPROVED
        )

        store.replaceAll(listOf(replacement))

        assertEquals(listOf(replacement), store.loadAll())
    }

    @Test
    fun remember_updatesExistingFaceStatusInsteadOfDuplicating() {
        val store = InMemoryFaceEmbeddingStore()
        val bannedFace = store.remember(
            FaceEmbedding(floatArrayOf(1f, 0f)),
            FaceAccessStatus.BANNED
        )

        val approvedFace = store.remember(
            FaceEmbedding(floatArrayOf(1f, 0f)),
            FaceAccessStatus.APPROVED,
            existingFaceId = bannedFace.id
        )

        assertEquals(bannedFace.id, approvedFace.id)
        assertEquals(FaceAccessStatus.APPROVED, approvedFace.accessStatus)
        assertEquals(listOf(approvedFace), store.loadAll())
    }

    @Test
    fun clear_removesRememberedFaces() {
        val store = InMemoryFaceEmbeddingStore()
        store.remember(FaceEmbedding(floatArrayOf(1f, 0f)), FaceAccessStatus.BANNED)

        store.clear()

        assertEquals(emptyList<RememberedFace>(), store.loadAll())
    }
}
