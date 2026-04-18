package com.example.ar_control.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class FitCenterLayoutCalculatorTest {

    @Test
    fun calculate_fitsLandscapeContentInsidePortraitBounds() {
        val fitted = FitCenterLayoutCalculator.calculate(
            containerWidth = 1000,
            containerHeight = 1000,
            contentWidth = 1920,
            contentHeight = 1080
        )

        assertEquals(1000, fitted.width)
        assertEquals(562, fitted.height)
    }

    @Test
    fun calculate_fitsPortraitContentInsideLandscapeBounds() {
        val fitted = FitCenterLayoutCalculator.calculate(
            containerWidth = 1000,
            containerHeight = 1000,
            contentWidth = 1080,
            contentHeight = 1920
        )

        assertEquals(562, fitted.width)
        assertEquals(1000, fitted.height)
    }
}
