package com.example.ar_control.face

import kotlin.math.sqrt

class FaceEmbeddingMatcher(
    private val matchThreshold: Float = DEFAULT_MATCH_THRESHOLD,
    private val minSimilarityMargin: Float = DEFAULT_MIN_SIMILARITY_MARGIN
) {
    fun findBestMatch(
        probe: FaceEmbedding,
        rememberedFaces: List<RememberedFace>
    ): FaceMatch? {
        val ranked = rememberedFaces
            .map { face ->
                FaceMatch(
                    face = face,
                    similarity = cosineSimilarity(probe, face.embedding)
                )
            }
            .sortedByDescending { match -> match.similarity }
        val best = ranked.firstOrNull() ?: return null
        if (best.similarity < matchThreshold) {
            return null
        }
        val second = ranked.getOrNull(1)
        if (second != null && best.similarity - second.similarity < minSimilarityMargin) {
            return null
        }
        return best
    }

    data class FaceMatch(
        val face: RememberedFace,
        val similarity: Float
    )

    companion object {
        const val DEFAULT_MATCH_THRESHOLD = 0.80f
        const val DEFAULT_MIN_SIMILARITY_MARGIN = 0.04f

        fun cosineSimilarity(first: FaceEmbedding, second: FaceEmbedding): Float {
            require(first.values.size == second.values.size) {
                "Face embedding dimensions must match"
            }
            var dot = 0.0
            var firstMagnitude = 0.0
            var secondMagnitude = 0.0
            for (index in first.values.indices) {
                val firstValue = first.values[index].toDouble()
                val secondValue = second.values[index].toDouble()
                dot += firstValue * secondValue
                firstMagnitude += firstValue * firstValue
                secondMagnitude += secondValue * secondValue
            }
            if (firstMagnitude == 0.0 || secondMagnitude == 0.0) {
                return 0f
            }
            return (dot / (sqrt(firstMagnitude) * sqrt(secondMagnitude))).toFloat()
        }
    }
}

