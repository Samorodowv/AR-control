package com.example.ar_control.face

import com.example.ar_control.detection.DetectionBoundingBox
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class FaceBoxStabilizerTest {
    @Test
    fun update_hidesSingleFrameFalsePositive() {
        val stabilizer = FaceBoxStabilizer(requiredHits = 2, maxMisses = 1)
        val box = FaceBoundingBox(DetectionBoundingBox(100f, 100f, 220f, 260f))

        assertTrue(stabilizer.update(listOf(box)).isEmpty())
        assertTrue(stabilizer.update(emptyList()).isEmpty())
    }

    @Test
    fun update_emitsBoxAfterConsecutiveOverlappingHits() {
        val stabilizer = FaceBoxStabilizer(requiredHits = 2, maxMisses = 1)

        assertTrue(
            stabilizer.update(
                listOf(FaceBoundingBox(DetectionBoundingBox(100f, 100f, 220f, 260f)))
            ).isEmpty()
        )
        val stable = stabilizer.update(
            listOf(FaceBoundingBox(DetectionBoundingBox(104f, 102f, 224f, 262f)))
        )

        assertEquals(1, stable.size)
        assertEquals(104f, stable.single().boundingBox.left, 0.001f)
    }

    @Test
    fun update_requiresConsecutiveHits() {
        val stabilizer = FaceBoxStabilizer(requiredHits = 2, maxMisses = 1)

        assertTrue(
            stabilizer.update(
                listOf(FaceBoundingBox(DetectionBoundingBox(100f, 100f, 220f, 260f)))
            ).isEmpty()
        )
        assertTrue(stabilizer.update(emptyList()).isEmpty())
        assertTrue(
            stabilizer.update(
                listOf(FaceBoundingBox(DetectionBoundingBox(104f, 102f, 224f, 262f)))
            ).isEmpty()
        )
        val stable = stabilizer.update(
            listOf(FaceBoundingBox(DetectionBoundingBox(108f, 104f, 228f, 264f)))
        )

        assertEquals(1, stable.size)
        assertEquals(108f, stable.single().boundingBox.left, 0.001f)
    }

    @Test
    fun update_reappearingBoxMustEarnConsecutiveHitsAfterStaleTrackExpires() {
        val stabilizer = FaceBoxStabilizer(requiredHits = 2, maxMisses = 1)

        assertTrue(stabilizer.update(listOf(faceBox(100f, 100f, 220f, 260f))).isEmpty())
        assertEquals(1, stabilizer.update(listOf(faceBox(104f, 102f, 224f, 262f))).size)
        assertTrue(stabilizer.update(emptyList()).isEmpty())
        assertTrue(stabilizer.update(emptyList()).isEmpty())
        assertTrue(stabilizer.update(listOf(faceBox(108f, 104f, 228f, 264f))).isEmpty())
        val stable = stabilizer.update(listOf(faceBox(112f, 106f, 232f, 266f)))

        assertEquals(1, stable.size)
        assertEquals(112f, stable.single().boundingBox.left, 0.001f)
    }

    private fun faceBox(
        left: Float,
        top: Float,
        right: Float,
        bottom: Float
    ): FaceBoundingBox {
        return FaceBoundingBox(DetectionBoundingBox(left, top, right, bottom))
    }
}
