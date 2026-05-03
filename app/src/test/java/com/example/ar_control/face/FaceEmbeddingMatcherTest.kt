package com.example.ar_control.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class FaceEmbeddingMatcherTest {

    @Test
    fun findBestMatch_returnsClosestEmbeddingAboveThreshold() {
        val matcher = FaceEmbeddingMatcher(matchThreshold = 0.75f)
        val rememberedFaces = listOf(
            RememberedFace(id = "face-1", label = "Face 1", embedding = FaceEmbedding(floatArrayOf(1f, 0f))),
            RememberedFace(id = "face-2", label = "Face 2", embedding = FaceEmbedding(floatArrayOf(0f, 1f)))
        )

        val match = matcher.findBestMatch(
            probe = FaceEmbedding(floatArrayOf(0.95f, 0.05f)),
            rememberedFaces = rememberedFaces
        )

        assertEquals("face-1", match?.face?.id)
        assertEquals("Face 1", match?.face?.label)
        assertEquals(0.9986f, match?.similarity ?: 0f, 0.001f)
    }

    @Test
    fun findBestMatch_returnsNullWhenSimilarityIsBelowThreshold() {
        val matcher = FaceEmbeddingMatcher(matchThreshold = 0.95f)
        val rememberedFaces = listOf(
            RememberedFace(id = "face-1", label = "Face 1", embedding = FaceEmbedding(floatArrayOf(1f, 0f)))
        )

        val match = matcher.findBestMatch(
            probe = FaceEmbedding(floatArrayOf(0f, 1f)),
            rememberedFaces = rememberedFaces
        )

        assertNull(match)
    }

    @Test
    fun cosineSimilarity_handlesZeroVectors() {
        assertEquals(
            0f,
            FaceEmbeddingMatcher.cosineSimilarity(
                FaceEmbedding(floatArrayOf(0f, 0f)),
                FaceEmbedding(floatArrayOf(1f, 0f))
            ),
            0.0001f
        )
    }
}
