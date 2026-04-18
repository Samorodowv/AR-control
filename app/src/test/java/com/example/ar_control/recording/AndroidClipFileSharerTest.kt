package com.example.ar_control.recording

import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.net.Uri
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import com.example.ar_control.ArControlApp

@Config(application = ArControlApp::class)
@RunWith(RobolectricTestRunner::class)
class AndroidClipFileSharerTest {

    @Test
    fun buildOpenIntentUsesContentUriMimeTypeAndReadGrant() {
        val clip = sampleClip()
        var observedFile: File? = null
        val expectedUri = Uri.parse("content://com.example.ar_control.fileprovider/recordings/clip.mp4")
        val sharer = AndroidClipFileSharer { file ->
            observedFile = file
            expectedUri
        }

        val intent = sharer.buildOpenIntent(clip)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals(expectedUri, intent.data)
        assertEquals(clip.mimeType, intent.type)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(File(clip.filePath), observedFile)
    }

    @Suppress("DEPRECATION")
    @Test
    fun buildShareIntentUsesStreamExtraMimeTypeAndReadGrant() {
        val clip = sampleClip()
        var observedFile: File? = null
        val expectedUri = Uri.parse("content://com.example.ar_control.fileprovider/recordings/clip.mp4")
        val sharer = AndroidClipFileSharer { file ->
            observedFile = file
            expectedUri
        }

        val intent = sharer.buildShareIntent(clip)

        assertEquals(Intent.ACTION_SEND, intent.action)
        assertEquals(clip.mimeType, intent.type)
        assertEquals(expectedUri, intent.extras?.get(Intent.EXTRA_STREAM))
        assertNotNull(intent.clipData)
        assertEquals(expectedUri, intent.clipData?.getItemAt(0)?.uri)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
        assertEquals(File(clip.filePath), observedFile)
    }

    @Test
    fun contextBackedResolverUsesComputedAuthorityAndBuildsChooserSafeIntent() {
        val context = object : ContextWrapper(RuntimeEnvironment.getApplication()) {
            override fun getApplicationContext(): Context = this
            override fun getPackageName(): String = "com.example.ar_control"
        }
        val clip = sampleClip()
        val expectedUri = Uri.parse("content://com.example.ar_control.fileprovider/recordings/clip.mp4")
        var observedContext: Context? = null
        var observedAuthority: String? = null
        var observedFile: File? = null

        val intent = AndroidClipFileSharer(context) { actualContext, authority, file ->
            observedContext = actualContext
            observedAuthority = authority
            observedFile = file
            expectedUri
        }.buildShareIntent(clip)

        assertEquals(context, observedContext)
        assertEquals("com.example.ar_control.fileprovider", observedAuthority)
        assertEquals(File(clip.filePath), observedFile)
        assertEquals(expectedUri, intent.extras?.get(Intent.EXTRA_STREAM))
        assertNotNull(intent.clipData)
        assertEquals(expectedUri, intent.clipData?.getItemAt(0)?.uri)
        assertEquals(clip.mimeType, intent.type)
        assertTrue((intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) != 0)
    }

    @Test
    fun manifestFileProviderConfigurationMatchesExpectedAuthorityAndPaths() {
        val declaredAuthority = readDeclaredFileProviderAuthority()
        val filePathsXml = readFilePathsXml()
        val providerBlock = readFileProviderBlock()

        assertEquals("\${applicationId}.fileprovider", declaredAuthority)
        assertTrue(providerBlock.contains("android:name=\"androidx.core.content.FileProvider\""))
        assertTrue(providerBlock.contains("android:grantUriPermissions=\"true\""))
        assertTrue(providerBlock.contains("android.support.FILE_PROVIDER_PATHS"))
        assertTrue(filePathsXml.contains("<external-files-path"))
        assertTrue(filePathsXml.contains("Movies/recordings/"))
    }

    private fun readDeclaredFileProviderAuthority(): String {
        val providerBlock = readFileProviderBlock()
        val match = Regex("""android:authorities="([^"]+)"""").find(providerBlock)
            ?: error("Could not find FileProvider authority in manifest")
        return match.groupValues[1]
    }

    private fun readFileProviderBlock(): String {
        val manifestFile = listOf(
            File("app/src/main/AndroidManifest.xml"),
            File("src/main/AndroidManifest.xml"),
            File("AndroidManifest.xml")
        ).firstOrNull { it.isFile } ?: error("Could not locate AndroidManifest.xml")
        val manifestText = manifestFile.readText()
        val match = Regex(
            """<provider\s+android:name="androidx\.core\.content\.FileProvider"[\s\S]*?</provider>"""
        ).find(manifestText) ?: error("Could not find FileProvider block in manifest")
        return match.value
    }

    private fun readFilePathsXml(): String {
        val filePaths = listOf(
            File("app/src/main/res/xml/file_paths.xml"),
            File("src/main/res/xml/file_paths.xml")
        ).firstOrNull { it.isFile }
            ?: error("Could not locate file_paths.xml")
        return filePaths.readText()
    }

    private fun sampleClip(filePath: String = File("Movies/recordings/clip.mp4").path): RecordedClip {
        return RecordedClip(
            id = "clip-1",
            filePath = filePath,
            createdAtEpochMillis = 1_700_000_000_000L,
            durationMillis = 42_000L,
            width = 1920,
            height = 1080,
            fileSizeBytes = 123_456L,
            mimeType = "video/mp4"
        )
    }
}
