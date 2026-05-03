package com.example.ar_control.face

import kotlin.math.sqrt

class FaceEmbeddingMatcher(
    private val matchThreshold: Float = DEFAULT_MATCH_THRESHOLD
) {
    fun findBestMatch(
        probe: FaceEmbedding,
        rememberedFaces: List<RememberedFace>
    ): FaceMatch? {
        return rememberedFaces
            .asSequence()
            .map { face ->
                FaceMatch(
                    face = face,
                    similarity = cosineSimilarity(probe, face.embedding)
                )
            }
            .filter { match -> match.similarity >= matchThreshold }
            .maxByOrNull { match -> match.similarity }
    }

    data class FaceMatch(
        val face: RememberedFace,
        val similarity: Float
    )

    companion object {
        const val DEFAULT_MATCH_THRESHOLD = 0.72f

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

