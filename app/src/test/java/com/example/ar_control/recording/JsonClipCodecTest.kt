package com.example.ar_control.recording

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonClipCodecTest {

    @Test
    fun encodeDecodeRoundTripsEscapedStrings() {
        val clip = RecordedClip(
            id = "quote\"backslash\\control\n\t\r\u000C\u0008 unicode ☃",
            filePath = "C:\\clips\\\"recording\"\\line\nbreak\tand\rmore\u000Ctext\u0008",
            createdAtEpochMillis = 123L,
            durationMillis = 456L,
            width = 1920,
            height = 1080,
            fileSizeBytes = 789L,
            mimeType = "video/mp4; title=\"A \\\"B\\\"\""
        )

        val encoded = JsonClipCodec.encodeCatalog(listOf(clip))
        val decoded = JsonClipCodec.decodeCatalog(encoded)

        assertTrue(decoded is JsonClipCatalogParseResult.Success)
        assertEquals(listOf(clip), (decoded as JsonClipCatalogParseResult.Success).clips)
    }

    @Test
    fun decodeCatalogParsesUnicodeEscapeSequences() {
        val raw = """[{"id":"unicode-\u0041-\u03c0","filePath":"path","createdAtEpochMillis":1,"durationMillis":2,"width":3,"height":4,"fileSizeBytes":5,"mimeType":"video\/mp4"}]"""

        val decoded = JsonClipCodec.decodeCatalog(raw)

        assertTrue(decoded is JsonClipCatalogParseResult.Success)
        val clip = (decoded as JsonClipCatalogParseResult.Success).clips.single()
        assertEquals("unicode-A-π", clip.id)
        assertEquals("video/mp4", clip.mimeType)
    }

    @Test
    fun decodeCatalogReportsMalformedInput() {
        val decoded = JsonClipCodec.decodeCatalog("""[{"id":"broken""")

        assertTrue(decoded is JsonClipCatalogParseResult.Malformed)
    }
}
