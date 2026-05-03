package com.example.ar_control.camera

import android.hardware.camera2.CameraCharacteristics
import org.junit.Assert.assertEquals
import org.junit.Test

class AndroidCameraPreviewRotationTest {

    @Test
    fun backCameraPortraitDisplayUsesSensorRotation() {
        assertEquals(
            90,
            calculateAndroidCameraPreviewRotationDegrees(
                sensorOrientationDegrees = 90,
                displayRotationDegrees = 0,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )
        )
    }

    @Test
    fun backCameraLandscapeDisplayCancelsSensorRotation() {
        assertEquals(
            0,
            calculateAndroidCameraPreviewRotationDegrees(
                sensorOrientationDegrees = 90,
                displayRotationDegrees = 90,
                lensFacing = CameraCharacteristics.LENS_FACING_BACK
            )
        )
    }
}
