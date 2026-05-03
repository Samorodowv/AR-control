package com.example.ar_control.face

data class FaceRecognitionState(
    val modelReady: Boolean = false,
    val faceCount: Int = 0,
    val bestFaceEmbedding: FaceEmbedding? = null,
    val matchedFace: RememberedFace? = null,
    val status: FaceRecognitionStatus = FaceRecognitionStatus.ModelMissing
)

enum class FaceRecognitionStatus {
    ModelMissing,
    NoFace,
    MultipleFaces,
    UnknownFace,
    RememberedFace
}

sealed interface FaceEnrollmentResult {
    data object ModelMissing : FaceEnrollmentResult
    data object NoFace : FaceEnrollmentResult
    data object MultipleFaces : FaceEnrollmentResult
    data class Remembered(val face: RememberedFace) : FaceEnrollmentResult
}

