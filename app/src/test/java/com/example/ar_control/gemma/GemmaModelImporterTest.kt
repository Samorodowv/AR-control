package com.example.ar_control.gemma

import android.net.Uri
import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.nio.file.Files
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class GemmaModelImporterTest {

    @Test
    fun importModelCopiesContentAndPersistsMetadata() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-test").toFile()
        val preferences = FakeGemmaSubtitlePreferences()
        val sourceBytes = byteArrayOf(1, 2, 3, 4)
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { ByteArrayInputStream(sourceBytes) }
        )

        val result = importer.importModel(Uri.parse("content://models/gemma"), "gemma-4.litertlm")

        assertTrue(result is GemmaModelImportResult.Imported)
        val imported = result as GemmaModelImportResult.Imported
        assertEquals(File(targetDirectory, "gemma-subtitles.litertlm").absolutePath, imported.path)
        assertEquals("gemma-4.litertlm", imported.displayName)
        assertTrue(File(imported.path).exists())
        assertArrayEquals(sourceBytes, File(imported.path).readBytes())
        assertEquals(imported.path, preferences.getModelPath())
        assertEquals("gemma-4.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun importModelUsesDefaultDisplayNameWhenProvidedNameIsBlank() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-default-name-test").toFile()
        val preferences = FakeGemmaSubtitlePreferences()
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { ByteArrayInputStream(byteArrayOf(7, 8)) }
        )

        val result = importer.importModel(Uri.parse("content://models/gemma"), "  ")

        assertTrue(result is GemmaModelImportResult.Imported)
        val imported = result as GemmaModelImportResult.Imported
        assertEquals("gemma-subtitles.litertlm", imported.displayName)
        assertEquals("gemma-subtitles.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun importModelReplacesExistingFinalModelWithNewBytes() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-final-replace-test").toFile()
        val existingModel = File(targetDirectory, "gemma-subtitles.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 1, 1))
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(existingModel.absolutePath, "old.litertlm")
        }
        val newBytes = byteArrayOf(2, 3, 4, 5)
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { ByteArrayInputStream(newBytes) }
        )

        val result = importer.importModel(Uri.parse("content://models/new-final"), "new.litertlm")

        assertTrue(result is GemmaModelImportResult.Imported)
        val imported = result as GemmaModelImportResult.Imported
        assertEquals(existingModel.absolutePath, imported.path)
        assertArrayEquals(newBytes, existingModel.readBytes())
        assertEquals(existingModel.absolutePath, preferences.getModelPath())
        assertEquals("new.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun importModelLeavesPreviousDifferentModelAfterSuccessfulImport() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-replace-test").toFile()
        val previous = File(targetDirectory, "previous.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(previous.absolutePath, "previous.litertlm")
        }
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { ByteArrayInputStream(byteArrayOf(5, 6)) }
        )

        val result = importer.importModel(Uri.parse("content://models/new"), "new.litertlm")

        assertTrue(result is GemmaModelImportResult.Imported)
        assertTrue(previous.exists())
        assertEquals("new.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun importModelKeepsExistingModelAndPreferencesWhenCopyFails() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-copy-failure-test").toFile()
        val existingBytes = byteArrayOf(10, 11, 12, 13)
        val existingModel = File(targetDirectory, "gemma-subtitles.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(existingBytes)
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(existingModel.absolutePath, "existing.litertlm")
        }
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { ThrowingAfterBytesInputStream(byteArrayOf(1, 2)) }
        )

        val result = importer.importModel(Uri.parse("content://models/failing"), "new.litertlm")

        assertEquals(GemmaModelImportResult.Failed("Could not import selected Gemma model"), result)
        assertArrayEquals(existingBytes, existingModel.readBytes())
        assertEquals(existingModel.absolutePath, preferences.getModelPath())
        assertEquals("existing.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun importModelReturnsFailureWhenSourceCloseThrowsDuringSetupFailure() = runTest {
        val targetDirectory = Files.createTempFile("gemma-import-not-directory", ".tmp").toFile()
        val preferences = FakeGemmaSubtitlePreferences()
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { CloseThrowingInputStream(byteArrayOf(1, 2)) }
        )

        val result = importer.importModel(Uri.parse("content://models/close-fails"), "new.litertlm")

        assertEquals(GemmaModelImportResult.Failed("Could not import selected Gemma model"), result)
        assertNull(preferences.getModelPath())
        assertNull(preferences.getModelDisplayName())
    }

    @Test
    fun importModelReturnsFailureWhenSourceCannotBeOpened() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-failure-test").toFile()
        val previous = File(targetDirectory, "previous.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(previous.absolutePath, "previous.litertlm")
        }
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { null }
        )

        val result = importer.importModel(Uri.parse("content://models/missing"), "missing.litertlm")

        assertEquals(GemmaModelImportResult.Failed("Could not open selected Gemma model"), result)
        assertTrue(previous.exists())
        assertEquals(previous.absolutePath, preferences.getModelPath())
        assertEquals("previous.litertlm", preferences.getModelDisplayName())
        assertNull(File(targetDirectory, "gemma-subtitles.litertlm").takeIf { it.exists() })
    }

    @Test(expected = CancellationException::class)
    fun importModelPropagatesCancellationFromOpenInputStream() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-cancellation-test").toFile()
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = FakeGemmaSubtitlePreferences(),
            openInputStream = { throw CancellationException("cancelled") }
        )

        importer.importModel(Uri.parse("content://models/cancelled"), "cancelled.litertlm")
    }

    @Test
    fun importModelCleansTempFileAndKeepsExistingStateWhenCopyIsCancelled() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-import-copy-cancellation-test").toFile()
        val existingBytes = byteArrayOf(20, 21, 22, 23)
        val existingModel = File(targetDirectory, "gemma-subtitles.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(existingBytes)
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(existingModel.absolutePath, "existing.litertlm")
        }
        val importer = GemmaModelImporter(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openInputStream = { CancellingAfterBytesInputStream(byteArrayOf(1, 2, 3)) }
        )

        try {
            importer.importModel(Uri.parse("content://models/cancels-during-copy"), "new.litertlm")
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancelled during copy", expected.message)
        }

        assertTrue(targetDirectory.listFiles { file -> file.extension == "tmp" }.orEmpty().isEmpty())
        assertArrayEquals(existingBytes, existingModel.readBytes())
        assertEquals(existingModel.absolutePath, preferences.getModelPath())
        assertEquals("existing.litertlm", preferences.getModelDisplayName())
    }
}

private class CloseThrowingInputStream(
    private val bytes: ByteArray
) : InputStream() {
    private var offset = 0

    override fun read(): Int {
        if (offset < bytes.size) {
            return bytes[offset++].toInt() and 0xff
        }
        return -1
    }

    override fun close() {
        throw IOException("simulated close failure")
    }
}

private class ThrowingAfterBytesInputStream(
    private val bytesBeforeFailure: ByteArray
) : InputStream() {
    private var offset = 0

    override fun read(): Int {
        if (offset < bytesBeforeFailure.size) {
            return bytesBeforeFailure[offset++].toInt() and 0xff
        }
        throw IOException("simulated copy failure")
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (offset < bytesBeforeFailure.size) {
            val count = minOf(len, bytesBeforeFailure.size - offset)
            bytesBeforeFailure.copyInto(buffer, off, offset, offset + count)
            offset += count
            return count
        }
        throw IOException("simulated copy failure")
    }
}

private class CancellingAfterBytesInputStream(
    private val bytesBeforeCancellation: ByteArray
) : InputStream() {
    private var offset = 0

    override fun read(): Int {
        if (offset < bytesBeforeCancellation.size) {
            return bytesBeforeCancellation[offset++].toInt() and 0xff
        }
        throw CancellationException("cancelled during copy")
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (offset < bytesBeforeCancellation.size) {
            val count = minOf(len, bytesBeforeCancellation.size - offset)
            bytesBeforeCancellation.copyInto(buffer, off, offset, offset + count)
            offset += count
            return count
        }
        throw CancellationException("cancelled during copy")
    }
}

private class FakeGemmaSubtitlePreferences : GemmaSubtitlePreferences {
    private var enabled = false
    private var modelPath: String? = null
    private var modelDisplayName: String? = null

    override fun isGemmaSubtitlesEnabled(): Boolean = enabled

    override fun setGemmaSubtitlesEnabled(enabled: Boolean) {
        this.enabled = enabled
    }

    override fun getModelPath(): String? = modelPath

    override fun getModelDisplayName(): String? = modelDisplayName

    override fun setModel(path: String, displayName: String?) {
        modelPath = path
        modelDisplayName = displayName
    }

    override fun clearModel() {
        modelPath = null
        modelDisplayName = null
    }
}
