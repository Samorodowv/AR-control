package com.example.ar_control.gemma

import java.io.File
import org.junit.Assert.assertTrue
import org.junit.Test

class LiteRtGemmaManifestTest {
    @Test
    fun manifestDeclaresOptionalGpuNativeLibrariesForLiteRtLm() {
        val manifestText = readManifestText()

        assertTrue(
            manifestText.hasOptionalNativeLibraryDeclaration("libvndksupport.so")
        )
        assertTrue(
            manifestText.hasOptionalNativeLibraryDeclaration("libOpenCL.so")
        )
    }

    private fun String.hasOptionalNativeLibraryDeclaration(libraryName: String): Boolean {
        return Regex(
            """<uses-native-library\b[\s\S]*?android:name="$libraryName"[\s\S]*?android:required="false"[\s\S]*?/>"""
        ).containsMatchIn(this)
    }

    private fun readManifestText(): String {
        val manifestFile = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
            File("AndroidManifest.xml")
        ).firstOrNull { file -> file.isFile }
            ?: error("Could not locate AndroidManifest.xml")
        return manifestFile.readText()
    }
}
