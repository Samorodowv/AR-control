package com.example.ar_control.ui.preview

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class VolumeUpDoublePressDetectorTest {

    @Test
    fun onPress_returnsDoublePressWhenSecondPressArrivesWithinWindow() {
        val detector = VolumeUpDoublePressDetector(doublePressWindowMillis = 600L)

        assertNull(detector.onPress(1_000L))

        assertEquals(
            VolumeUpDoublePressDetector.Action.DoublePress,
            detector.onPress(1_450L)
        )
    }

    @Test
    fun consumePendingSinglePress_returnsSinglePressAfterWindowExpires() {
        val detector = VolumeUpDoublePressDetector(doublePressWindowMillis = 600L)

        assertNull(detector.onPress(1_000L))
        assertNull(detector.consumePendingSinglePress(1_599L))

        assertEquals(
            VolumeUpDoublePressDetector.Action.SinglePress,
            detector.consumePendingSinglePress(1_600L)
        )
        assertNull(detector.consumePendingSinglePress(1_700L))
    }

    @Test
    fun lateSecondPressStartsNewPendingSinglePress() {
        val detector = VolumeUpDoublePressDetector(doublePressWindowMillis = 600L)

        assertNull(detector.onPress(1_000L))
        assertEquals(
            VolumeUpDoublePressDetector.Action.SinglePress,
            detector.onPress(1_700L)
        )
        assertEquals(
            VolumeUpDoublePressDetector.Action.DoublePress,
            detector.onPress(2_100L)
        )
    }
}
