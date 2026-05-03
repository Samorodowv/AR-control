package com.example.ar_control.face

class FaceEmbedding(values: FloatArray) {
    val values: FloatArray = values.copyOf()

    init {
        require(values.isNotEmpty()) { "Face embedding cannot be empty" }
    }

    override fun equals(other: Any?): Boolean {
        return other is FaceEmbedding && values.contentEquals(other.values)
    }

    override fun hashCode(): Int = values.contentHashCode()
}

data class RememberedFace(
    val id: String,
    val label: String,
    val embedding: FaceEmbedding
)

