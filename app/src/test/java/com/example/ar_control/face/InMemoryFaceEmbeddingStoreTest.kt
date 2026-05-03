package com.example.ar_control.face

import org.junit.Assert.assertEquals
import org.junit.Test

class InMemoryFaceEmbeddingStoreTest {

    @Test
    fun rememberFace_appendsFacesWithStableLabels() {
        val store = InMemoryFaceEmbeddingStore()

        val first = store.remember(FaceEmbedding(floatArrayOf(1f, 0f)))
        val second = store.remember(FaceEmbedding(floatArrayOf(0f, 1f)))

        assertEquals("Face 1", first.label)
        assertEquals("Face 2", second.label)
        assertEquals(listOf(first, second), store.loadAll())
    }

    @Test
    fun replaceAll_overwritesRememberedFaces() {
        val store = InMemoryFaceEmbeddingStore()
        store.remember(FaceEmbedding(floatArrayOf(1f, 0f)))
        val replacement = RememberedFace(
            id = "manual-id",
            label = "Nikolay",
            embedding = FaceEmbedding(floatArrayOf(0.5f, 0.5f))
        )

        store.replaceAll(listOf(replacement))

        assertEquals(listOf(replacement), store.loadAll())
    }
}
