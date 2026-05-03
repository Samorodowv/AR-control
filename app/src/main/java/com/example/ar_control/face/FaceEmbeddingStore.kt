package com.example.ar_control.face

interface FaceEmbeddingStore {
    fun loadAll(): List<RememberedFace>

    fun remember(embedding: FaceEmbedding): RememberedFace

    fun replaceAll(faces: List<RememberedFace>)
}

class InMemoryFaceEmbeddingStore : FaceEmbeddingStore {
    private val rememberedFaces = mutableListOf<RememberedFace>()

    override fun loadAll(): List<RememberedFace> = rememberedFaces.toList()

    override fun remember(embedding: FaceEmbedding): RememberedFace {
        val nextIndex = rememberedFaces.size + 1
        val face = RememberedFace(
            id = "face-$nextIndex",
            label = "Face $nextIndex",
            embedding = embedding
        )
        rememberedFaces += face
        return face
    }

    override fun replaceAll(faces: List<RememberedFace>) {
        rememberedFaces.clear()
        rememberedFaces += faces
    }
}
