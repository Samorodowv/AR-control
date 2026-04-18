package com.example.ar_control.preview

import org.junit.Assert.assertEquals
import org.junit.Test

class AspectRatioTextureViewTransformTest {

    @Test
    fun zoomScale_isIdentityAtDefaultZoom() {
        val transform = PreviewTransformCalculator.calculate(zoomFactor = 1.0f)

        assertEquals(1.0f, transform.scaleX)
        assertEquals(1.0f, transform.scaleY)
    }

    @Test
    fun zoomScale_matchesRequestedZoomFactor() {
        val transform = PreviewTransformCalculator.calculate(zoomFactor = 1.8f)

        assertEquals(1.8f, transform.scaleX)
        assertEquals(1.8f, transform.scaleY)
    }
}
