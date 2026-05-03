package com.example.ar_control.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceEnrollmentControllerTest {

    @Test
    fun rememberCurrentFace_returnsModelMissingWhenModelIsNotReady() {
        val controller = FaceEnrollmentController(InMemoryFaceEmbeddingStore())

        val result = controller.rememberCurrentFace(
            FaceRecognitionState(modelReady = false),
            FaceAccessStatus.BANNED
        )

        assertEquals(FaceEnrollmentResult.ModelMissing, result)
    }

    @Test
    fun rememberCurrentFace_returnsNoFaceWhenNoSingleEmbeddingIsAvailable() {
        val controller = FaceEnrollmentController(InMemoryFaceEmbeddingStore())

        val result = controller.rememberCurrentFace(
            FaceRecognitionState(modelReady = true, faceCount = 0),
            FaceAccessStatus.BANNED
        )

        assertEquals(FaceEnrollmentResult.NoFace, result)
    }

    @Test
    fun rememberCurrentFace_blocksMultipleFaces() {
        val controller = FaceEnrollmentController(InMemoryFaceEmbeddingStore())

        val result = controller.rememberCurrentFace(
            FaceRecognitionState(
                modelReady = true,
                faceCount = 2,
                bestFaceEmbedding = FaceEmbedding(floatArrayOf(1f, 0f))
            ),
            FaceAccessStatus.BANNED
        )

        assertEquals(FaceEnrollmentResult.MultipleFaces, result)
    }

    @Test
    fun rememberCurrentFace_storesSingleFaceEmbeddingWithRequestedAccessStatus() {
        val store = InMemoryFaceEmbeddingStore()
        val controller = FaceEnrollmentController(store)

        val result = controller.rememberCurrentFace(
            FaceRecognitionState(
                modelReady = true,
                faceCount = 1,
                bestFaceEmbedding = FaceEmbedding(floatArrayOf(1f, 0f))
            ),
            FaceAccessStatus.BANNED
        )

        assertTrue(result is FaceEnrollmentResult.Remembered)
        assertEquals("Face 1", (result as FaceEnrollmentResult.Remembered).face.label)
        assertEquals(FaceAccessStatus.BANNED, result.face.accessStatus)
        assertEquals(listOf(result.face), store.loadAll())
    }

    @Test
    fun rememberCurrentFace_updatesExistingMatchedFaceAccessStatus() {
        val store = InMemoryFaceEmbeddingStore()
        val controller = FaceEnrollmentController(store)
        val embedding = FaceEmbedding(floatArrayOf(1f, 0f))
        val bannedFace = store.remember(embedding, FaceAccessStatus.BANNED)

        val result = controller.rememberCurrentFace(
            FaceRecognitionState(
                modelReady = true,
                faceCount = 1,
                bestFaceEmbedding = embedding,
                matchedFace = bannedFace
            ),
            FaceAccessStatus.APPROVED
        )

        assertTrue(result is FaceEnrollmentResult.Remembered)
        val approvedFace = (result as FaceEnrollmentResult.Remembered).face
        assertEquals(bannedFace.id, approvedFace.id)
        assertEquals(FaceAccessStatus.APPROVED, approvedFace.accessStatus)
        assertEquals(listOf(approvedFace), store.loadAll())
    }
}
