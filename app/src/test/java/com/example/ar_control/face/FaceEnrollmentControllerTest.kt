package com.example.ar_control.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceEnrollmentControllerTest {

    @Test
    fun rememberCurrentFace_returnsModelMissingWhenModelIsNotReady() {
        val controller = FaceEnrollmentController(InMemoryFaceEmbeddingStore())

        val result = controller.rememberCurrentFace(FaceRecognitionState(modelReady = false))

        assertEquals(FaceEnrollmentResult.ModelMissing, result)
    }

    @Test
    fun rememberCurrentFace_returnsNoFaceWhenNoSingleEmbeddingIsAvailable() {
        val controller = FaceEnrollmentController(InMemoryFaceEmbeddingStore())

        val result = controller.rememberCurrentFace(
            FaceRecognitionState(modelReady = true, faceCount = 0)
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
            )
        )

        assertEquals(FaceEnrollmentResult.MultipleFaces, result)
    }

    @Test
    fun rememberCurrentFace_storesSingleFaceEmbedding() {
        val store = InMemoryFaceEmbeddingStore()
        val controller = FaceEnrollmentController(store)

        val result = controller.rememberCurrentFace(
            FaceRecognitionState(
                modelReady = true,
                faceCount = 1,
                bestFaceEmbedding = FaceEmbedding(floatArrayOf(1f, 0f))
            )
        )

        assertTrue(result is FaceEnrollmentResult.Remembered)
        assertEquals("Face 1", (result as FaceEnrollmentResult.Remembered).face.label)
        assertEquals(listOf(result.face), store.loadAll())
    }
}
