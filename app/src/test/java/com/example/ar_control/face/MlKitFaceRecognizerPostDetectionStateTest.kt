package com.example.ar_control.face

import com.example.ar_control.detection.DetectionBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MlKitFaceRecognizerPostDetectionStateTest {
    @Test
    fun preEmbeddingState_countsAcceptedCandidatesWhenOnlyOneBoxIsStable() {
        val stableBox = FaceBoundingBox(DetectionBoundingBox(100f, 100f, 220f, 260f))

        val state = preEmbeddingFaceRecognitionState(
            acceptedCandidateCount = 2,
            stableBoxes = listOf(stableBox)
        )

        requireNotNull(state)
        assertEquals(FaceRecognitionStatus.MultipleFaces, state.status)
        assertEquals(2, state.faceCount)
        assertEquals(listOf(stableBox), state.faceBoxes)
    }

    @Test
    fun preEmbeddingState_allowsEmbeddingForOneAcceptedStableCandidate() {
        val stableBox = FaceBoundingBox(DetectionBoundingBox(100f, 100f, 220f, 260f))

        val state = preEmbeddingFaceRecognitionState(
            acceptedCandidateCount = 1,
            stableBoxes = listOf(stableBox)
        )

        assertNull(state)
    }
}
