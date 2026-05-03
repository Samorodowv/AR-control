package com.example.ar_control.face

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertNull
import org.junit.Test

class FaceIdentityStabilizerTest {
    private val bannedFace = RememberedFace(
        id = "face-1",
        label = "Face 1",
        embedding = FaceEmbedding(floatArrayOf(1f, 0f)),
        accessStatus = FaceAccessStatus.BANNED
    )
    private val approvedFace = RememberedFace(
        id = "face-2",
        label = "Face 2",
        embedding = FaceEmbedding(floatArrayOf(0f, 1f)),
        accessStatus = FaceAccessStatus.APPROVED
    )

    @Test
    fun decide_requiresTwoConsecutiveMatchesBeforeColoring() {
        val stabilizer = FaceIdentityStabilizer(requiredConsecutiveMatches = 2)

        assertNull(stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.91f)))
        assertEquals(
            bannedFace,
            stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.93f))
        )
    }

    @Test
    fun decide_returnsNullWhenIdentityChangesUntilNewIdentityIsConfirmed() {
        val stabilizer = FaceIdentityStabilizer(requiredConsecutiveMatches = 2)

        stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.91f))
        assertEquals(
            bannedFace,
            stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.92f))
        )
        assertNull(stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(approvedFace, 0.93f)))
        assertEquals(
            approvedFace,
            stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(approvedFace, 0.94f))
        )
    }

    @Test
    fun decide_returnsNullForAmbiguousFrames() {
        val stabilizer = FaceIdentityStabilizer(requiredConsecutiveMatches = 2)

        assertNull(stabilizer.decide(null))
    }

    @Test
    fun decide_clearsPendingCandidateWhenStableIdentityAppearsAgain() {
        val stabilizer = FaceIdentityStabilizer(requiredConsecutiveMatches = 2)

        stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.91f))
        assertEquals(
            bannedFace,
            stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.92f))
        )
        assertNull(stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(approvedFace, 0.93f)))
        assertEquals(
            bannedFace,
            stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.94f))
        )
        assertNull(stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(approvedFace, 0.95f)))
        assertEquals(
            approvedFace,
            stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(approvedFace, 0.96f))
        )
    }

    @Test
    fun decide_returnsNullAfterStableStateWasClearedByAmbiguousFrame() {
        val stabilizer = FaceIdentityStabilizer(requiredConsecutiveMatches = 2)

        stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.91f))
        assertEquals(
            bannedFace,
            stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.92f))
        )
        assertNull(stabilizer.decide(null))

        assertNull(stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.93f)))
    }

    @Test
    fun decide_returnsNullAfterCandidateWasClearedByAmbiguousFrame() {
        val stabilizer = FaceIdentityStabilizer(requiredConsecutiveMatches = 2)

        assertNull(stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.91f)))
        assertNull(stabilizer.decide(null))

        assertNull(stabilizer.decide(FaceEmbeddingMatcher.FaceMatch(bannedFace, 0.92f)))
    }

    @Test
    fun init_rejectsNonPositiveRequiredConsecutiveMatches() {
        assertThrows(IllegalArgumentException::class.java) {
            FaceIdentityStabilizer(requiredConsecutiveMatches = 0)
        }
    }
}
