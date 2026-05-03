package com.example.ar_control.face

class FaceEnrollmentController(
    private val embeddingStore: FaceEmbeddingStore,
    private val duplicateThreshold: Float = 0.88f
) {
    fun rememberCurrentFace(
        state: FaceRecognitionState,
        accessStatus: FaceAccessStatus
    ): FaceEnrollmentResult {
        if (!state.modelReady) {
            return FaceEnrollmentResult.ModelMissing
        }
        if (state.faceCount > 1) {
            return FaceEnrollmentResult.MultipleFaces
        }
        val embedding = state.bestFaceEmbedding ?: return FaceEnrollmentResult.NoFace
        if (state.faceCount != 1) {
            return FaceEnrollmentResult.NoFace
        }
        val existingFaceId = state.matchedFace?.id ?: findDuplicateFace(embedding)?.id
        return FaceEnrollmentResult.Remembered(
            embeddingStore.remember(
                embedding = embedding,
                accessStatus = accessStatus,
                existingFaceId = existingFaceId
            )
        )
    }

    private fun findDuplicateFace(embedding: FaceEmbedding): RememberedFace? {
        return FaceEmbeddingMatcher(matchThreshold = duplicateThreshold)
            .findBestMatch(
                probe = embedding,
                rememberedFaces = embeddingStore.loadAll()
            )
            ?.face
    }
}

