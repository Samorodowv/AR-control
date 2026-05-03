package com.example.ar_control.face

class FaceEnrollmentController(
    private val embeddingStore: FaceEmbeddingStore
) {
    fun rememberCurrentFace(state: FaceRecognitionState): FaceEnrollmentResult {
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
        return FaceEnrollmentResult.Remembered(embeddingStore.remember(embedding))
    }
}

