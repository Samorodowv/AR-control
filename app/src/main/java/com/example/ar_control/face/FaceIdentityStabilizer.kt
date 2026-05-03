package com.example.ar_control.face

class FaceIdentityStabilizer(
    private val requiredConsecutiveMatches: Int = 2
) {
    init {
        require(requiredConsecutiveMatches >= 1) {
            "Required consecutive matches must be at least 1"
        }
    }

    private var candidateFaceId: String? = null
    private var candidateCount: Int = 0
    private var stableFaceId: String? = null

    fun decide(match: FaceEmbeddingMatcher.FaceMatch?): RememberedFace? {
        if (match == null) {
            candidateFaceId = null
            candidateCount = 0
            stableFaceId = null
            return null
        }
        if (match.face.id == stableFaceId) {
            candidateFaceId = null
            candidateCount = 0
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
        stableFaceId = match.face.id
        return match.face
    }
}
