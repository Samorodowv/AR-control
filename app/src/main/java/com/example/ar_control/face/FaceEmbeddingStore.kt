package com.example.ar_control.face

interface FaceEmbeddingStore {
    fun loadAll(): List<RememberedFace>

    fun remember(
        embedding: FaceEmbedding,
        accessStatus: FaceAccessStatus = FaceAccessStatus.APPROVED,
        existingFaceId: String? = null
    ): RememberedFace

    fun replaceAll(faces: List<RememberedFace>)

    fun clear() {
        replaceAll(emptyList())
    }
}

class InMemoryFaceEmbeddingStore : FaceEmbeddingStore {
    private val rememberedFaces = mutableListOf<RememberedFace>()

    override fun loadAll(): List<RememberedFace> = rememberedFaces.toList()

    override fun remember(
        embedding: FaceEmbedding,
        accessStatus: FaceAccessStatus,
        existingFaceId: String?
    ): RememberedFace {
        val existingIndex = rememberedFaces.indexOfFirst { face -> face.id == existingFaceId }
        if (existingIndex >= 0) {
            val updated = rememberedFaces[existingIndex].copy(
                embedding = embedding,
                accessStatus = accessStatus
            )
            rememberedFaces[existingIndex] = updated
            return updated
        }
        val nextIndex = rememberedFaces.size + 1
        val face = RememberedFace(
            id = "face-$nextIndex",
            label = "Face $nextIndex",
            embedding = embedding,
            accessStatus = accessStatus
        )
        rememberedFaces += face
        return face
    }

    override fun replaceAll(faces: List<RememberedFace>) {
        rememberedFaces.clear()
        rememberedFaces += faces
    }
}
