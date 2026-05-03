package com.example.ar_control.face

import com.example.ar_control.detection.DetectionBoundingBox

data class FaceRecognitionState(
    val modelReady: Boolean = false,
    val faceCount: Int = 0,
    val bestFaceEmbedding: FaceEmbedding? = null,
    val matchedFace: RememberedFace? = null,
    val faceBoxes: List<FaceBoundingBox> = emptyList(),
    val status: FaceRecognitionStatus = FaceRecognitionStatus.ModelMissing,
    val lastDetectionMillis: Long = 0L,
    val lastEmbeddingMillis: Long = 0L,
    val acceptedFrameCount: Long = 0L,
    val rejectedFrameCount: Long = 0L
)

data class FaceBoundingBox(
    val boundingBox: DetectionBoundingBox,
    val accessStatus: FaceAccessStatus? = null
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

