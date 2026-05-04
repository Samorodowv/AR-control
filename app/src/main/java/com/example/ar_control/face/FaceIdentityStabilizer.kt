package com.example.ar_control.face

class FaceIdentityStabilizer(
    private val requiredConsecutiveMatches: Int = 2,
    private val maxRetainedMisses: Int = 3
) {
    init {
        require(requiredConsecutiveMatches >= 1) {
            "Required consecutive matches must be at least 1"
        }
        require(maxRetainedMisses >= 0) {
            "Maximum retained misses must not be negative"
        }
    }

    private var candidateFaceId: String? = null
    private var candidateCount: Int = 0
    private var stableFace: RememberedFace? = null
    private var retainedMissCount: Int = 0

    fun seedStableFace(face: RememberedFace) {
        candidateFaceId = null
        candidateCount = 0
        stableFace = face
        retainedMissCount = 0
    }

    fun decide(
        match: FaceEmbeddingMatcher.FaceMatch?,
        retainStableIdentityOnMiss: Boolean = false
    ): RememberedFace? {
        if (match == null) {
            candidateFaceId = null
            candidateCount = 0
            val retainedFace = stableFace
            if (
                retainStableIdentityOnMiss &&
                retainedFace != null &&
                retainedMissCount < maxRetainedMisses
            ) {
                retainedMissCount += 1
                return retainedFace
            }
            stableFace = null
            retainedMissCount = 0
            return null
        }
        retainedMissCount = 0
        if (match.face.id == stableFace?.id) {
            candidateFaceId = null
            candidateCount = 0
            stableFace = match.face
            return match.face
        }
        if (match.face.id == candidateFaceId) {
            candidateCount += 1
        } else {
            candidateFaceId = match.face.id
            candidateCount = 1
        }
        if (candidateCount < requiredConsecutiveMatches) {
            return null
        }
        candidateFaceId = null
        candidateCount = 0
        stableFace = match.face
        return match.face
    }
}
