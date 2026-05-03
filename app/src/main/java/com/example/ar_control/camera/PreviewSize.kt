package com.example.ar_control.camera

data class PreviewSize(
    val width: Int,
    val height: Int,
    val rotationDegrees: Int = 0
) {
    val displayWidth: Int
        get() = if (isSidewaysRotation) height else width

    val displayHeight: Int
        get() = if (isSidewaysRotation) width else height

    private val isSidewaysRotation: Boolean
        get() = normalizedRotationDegrees == 90 || normalizedRotationDegrees == 270

    val normalizedRotationDegrees: Int
        get() = ((rotationDegrees % FULL_ROTATION_DEGREES) + FULL_ROTATION_DEGREES) %
            FULL_ROTATION_DEGREES

    private companion object {
        const val FULL_ROTATION_DEGREES = 360
    }
}
