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

    @Test
    fun rememberCurrentFace_doesNotScanStoredFacesWhenCurrentMatchExists() {
        val embedding = FaceEmbedding(floatArrayOf(1f, 0f))
        val bannedFace = RememberedFace(
            id = "face-1",
            label = "Face 1",
            embedding = embedding,
            accessStatus = FaceAccessStatus.BANNED
        )
        val store = LoadAllFailingFaceEmbeddingStore(bannedFace)
        val controller = FaceEnrollmentController(store)

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
    }

    @Test
    fun rememberCurrentFace_updatesHighlySimilarStoredFaceEvenWithoutCurrentMatch() {
        val store = InMemoryFaceEmbeddingStore()
        val controller = FaceEnrollmentController(store)
        val bannedFace = store.remember(
            embedding = FaceEmbedding(floatArrayOf(1f, 0f)),
            accessStatus = FaceAccessStatus.BANNED
        )

        val result = controller.rememberCurrentFace(
            FaceRecognitionState(
                modelReady = true,
                faceCount = 1,
                bestFaceEmbedding = FaceEmbedding(floatArrayOf(0.999f, 0.01f)),
                matchedFace = null
            ),
            FaceAccessStatus.APPROVED
        )

        assertTrue(result is FaceEnrollmentResult.Remembered)
        val approvedFace = (result as FaceEnrollmentResult.Remembered).face
        assertEquals(bannedFace.id, approvedFace.id)
        assertEquals(FaceAccessStatus.APPROVED, approvedFace.accessStatus)
        assertEquals(listOf(approvedFace), store.loadAll())
    }

    @Test
    fun rememberCurrentFace_appendsFaceWhenDuplicateLookupIsAmbiguous() {
        val store = InMemoryFaceEmbeddingStore()
        val controller = FaceEnrollmentController(store)
        val firstFace = store.remember(
            embedding = FaceEmbedding(floatArrayOf(1f, 0f)),
            accessStatus = FaceAccessStatus.BANNED
        )
        val secondFace = store.remember(
            embedding = FaceEmbedding(floatArrayOf(0.999f, 0.045f)),
            accessStatus = FaceAccessStatus.APPROVED
        )

        val result = controller.rememberCurrentFace(
            FaceRecognitionState(
                modelReady = true,
                faceCount = 1,
                bestFaceEmbedding = FaceEmbedding(floatArrayOf(1f, 0f)),
                matchedFace = null
            ),
            FaceAccessStatus.APPROVED
        )

        assertTrue(result is FaceEnrollmentResult.Remembered)
        val newFace = (result as FaceEnrollmentResult.Remembered).face
        assertTrue(newFace.id != firstFace.id)
        assertTrue(newFace.id != secondFace.id)
        assertEquals(FaceAccessStatus.APPROVED, newFace.accessStatus)
        assertEquals(listOf(firstFace, secondFace, newFace), store.loadAll())
    }

    @Test
    fun rememberCurrentFace_appendsFaceWhenSimilarStoredFaceIsBelowDuplicateThreshold() {
        val store = InMemoryFaceEmbeddingStore()
        val controller = FaceEnrollmentController(store)
        val existingFace = store.remember(
            embedding = FaceEmbedding(floatArrayOf(0.87f, 0.493f)),
            accessStatus = FaceAccessStatus.BANNED
        )

        val result = controller.rememberCurrentFace(
            FaceRecognitionState(
                modelReady = true,
                faceCount = 1,
                bestFaceEmbedding = FaceEmbedding(floatArrayOf(1f, 0f)),
                matchedFace = null
            ),
            FaceAccessStatus.APPROVED
        )

        assertTrue(result is FaceEnrollmentResult.Remembered)
        val newFace = (result as FaceEnrollmentResult.Remembered).face
        assertTrue(newFace.id != existingFace.id)
        assertEquals(FaceAccessStatus.APPROVED, newFace.accessStatus)
        assertEquals(listOf(existingFace, newFace), store.loadAll())
    }

    private class LoadAllFailingFaceEmbeddingStore(
        private val face: RememberedFace
    ) : FaceEmbeddingStore {
        override fun loadAll(): List<RememberedFace> {
            throw AssertionError("loadAll should not be called when matchedFace is present")
        }

        override fun remember(
            embedding: FaceEmbedding,
            accessStatus: FaceAccessStatus,
            existingFaceId: String?
        ): RememberedFace {
            assertEquals(face.id, existingFaceId)
            return face.copy(
                embedding = embedding,
                accessStatus = accessStatus
            )
        }

        override fun replaceAll(faces: List<RememberedFace>) = Unit
    }
}
