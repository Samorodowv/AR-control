package com.example.ar_control.ui.preview

import android.graphics.Color
import com.example.ar_control.face.FaceAccessStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class DetectionOverlayViewTest {

    @Test
    fun faceBoxColor_usesAccessStatusColorsAndWhiteForUnknownFaces() {
        assertEquals(Color.RED, faceBoxColor(FaceAccessStatus.BANNED))
        assertEquals(Color.GREEN, faceBoxColor(FaceAccessStatus.APPROVED))
        assertEquals(Color.WHITE, faceBoxColor(null))
    }
}
