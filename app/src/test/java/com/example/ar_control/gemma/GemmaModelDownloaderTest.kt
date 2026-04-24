package com.example.ar_control.gemma

import java.io.ByteArrayInputStream
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.URL
import java.nio.file.Files
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
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
class GemmaModelDownloaderTest {

    @Test
    fun downloadModelCopiesContentAndPersistsMetadata() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-test").toFile()
        val preferences = FakeGemmaSubtitlePreferences()
        val sourceBytes = byteArrayOf(1, 2, 3, 4)
        val sourceUrl = URL("https://example.test/gemma.litertlm")
        var openedUrl: URL? = null
        val downloader = GemmaModelDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            source = GemmaModelDownloadSource(
                url = sourceUrl,
                displayName = "gemma-4-E2B-it.litertlm",
                expectedSha256Hex = sha256Hex(sourceBytes)
            ),
            openStream = { url ->
                openedUrl = url
                GemmaModelDownloadStream(
                    inputStream = ByteArrayInputStream(sourceBytes),
                    contentLengthBytes = sourceBytes.size.toLong()
                )
            }
        )
        val progress = mutableListOf<GemmaModelDownloadProgress>()

        val result = downloader.downloadModel(progress::add)

        assertTrue(result is GemmaModelDownloadResult.Downloaded)
        val downloaded = result as GemmaModelDownloadResult.Downloaded
        assertEquals(File(targetDirectory, "gemma-subtitles.litertlm").absolutePath, downloaded.path)
        assertEquals("gemma-4-E2B-it.litertlm", downloaded.displayName)
        assertArrayEquals(sourceBytes, File(downloaded.path).readBytes())
        assertEquals(downloaded.path, preferences.getModelPath())
        assertEquals("gemma-4-E2B-it.litertlm", preferences.getModelDisplayName())
        assertTrue(progress.last().bytesDownloaded == sourceBytes.size.toLong())
        assertEquals(sourceBytes.size.toLong(), progress.last().totalBytes)
        assertEquals(sourceUrl, openedUrl)
    }

    @Test
    fun downloadModelReplacesExistingFinalModelWithNewBytes() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-final-replace-test").toFile()
        val existingModel = File(targetDirectory, "gemma-subtitles.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(1, 1, 1))
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(existingModel.absolutePath, "old.litertlm")
        }
        val newBytes = byteArrayOf(2, 3, 4, 5)
        val downloader = newDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            expectedSha256Hex = sha256Hex(newBytes),
            openStream = { _ ->
                GemmaModelDownloadStream(
                    inputStream = ByteArrayInputStream(newBytes),
                    contentLengthBytes = newBytes.size.toLong()
                )
            }
        )

        val result = downloader.downloadModel()

        assertTrue(result is GemmaModelDownloadResult.Downloaded)
        val downloaded = result as GemmaModelDownloadResult.Downloaded
        assertEquals(existingModel.absolutePath, downloaded.path)
        assertArrayEquals(newBytes, existingModel.readBytes())
        assertEquals(existingModel.absolutePath, preferences.getModelPath())
        assertEquals("gemma-4-E2B-it.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun downloadModelLeavesPreviousDifferentModelAfterSuccessfulDownload() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-replace-test").toFile()
        val previous = File(targetDirectory, "previous.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(previous.absolutePath, "previous.litertlm")
        }
        val downloader = newDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            expectedSha256Hex = sha256Hex(byteArrayOf(5, 6)),
            openStream = { _ ->
                GemmaModelDownloadStream(
                    inputStream = ByteArrayInputStream(byteArrayOf(5, 6)),
                    contentLengthBytes = 2L
                )
            }
        )

        val result = downloader.downloadModel()

        assertTrue(result is GemmaModelDownloadResult.Downloaded)
        assertTrue(previous.exists())
        assertEquals("gemma-4-E2B-it.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun downloadModelKeepsExistingModelAndPreferencesWhenCopyFails() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-copy-failure-test").toFile()
        val existingBytes = byteArrayOf(10, 11, 12, 13)
        val existingModel = File(targetDirectory, "gemma-subtitles.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(existingBytes)
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(existingModel.absolutePath, "existing.litertlm")
        }
        val downloader = newDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openStream = { _ ->
                GemmaModelDownloadStream(
                    inputStream = ThrowingAfterBytesInputStream(byteArrayOf(1, 2)),
                    contentLengthBytes = 2L
                )
            }
        )

        val result = downloader.downloadModel()

        assertEquals(GemmaModelDownloadResult.Failed("Could not download Gemma model"), result)
        assertArrayEquals(existingBytes, existingModel.readBytes())
        assertEquals(existingModel.absolutePath, preferences.getModelPath())
        assertEquals("existing.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun downloadModelKeepsExistingModelAndPreferencesWhenChecksumDoesNotMatch() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-checksum-mismatch-test").toFile()
        val existingBytes = byteArrayOf(50, 51, 52, 53)
        val existingModel = File(targetDirectory, "gemma-subtitles.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(existingBytes)
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(existingModel.absolutePath, "existing.litertlm")
        }
        val newBytes = byteArrayOf(1, 2, 3, 4)
        val downloader = newDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            expectedSha256Hex = sha256Hex(byteArrayOf(9, 9, 9, 9)),
            openStream = { _ ->
                GemmaModelDownloadStream(
                    inputStream = ByteArrayInputStream(newBytes),
                    contentLengthBytes = newBytes.size.toLong()
                )
            }
        )

        val result = downloader.downloadModel()

        assertEquals(GemmaModelDownloadResult.Failed("Could not download Gemma model"), result)
        assertTrue(targetDirectory.listFiles { file -> file.extension == "tmp" }.orEmpty().isEmpty())
        assertArrayEquals(existingBytes, existingModel.readBytes())
        assertEquals(existingModel.absolutePath, preferences.getModelPath())
        assertEquals("existing.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun downloadModelReturnsFailureWhenSourceCloseThrowsDuringSetupFailure() = runTest {
        val targetDirectory = Files.createTempFile("gemma-download-not-directory", ".tmp").toFile()
        val preferences = FakeGemmaSubtitlePreferences()
        val downloader = newDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openStream = { _ ->
                GemmaModelDownloadStream(
                    inputStream = CloseThrowingInputStream(byteArrayOf(1, 2)),
                    contentLengthBytes = 2L
                )
            }
        )

        val result = downloader.downloadModel()

        assertEquals(GemmaModelDownloadResult.Failed("Could not download Gemma model"), result)
        assertNull(preferences.getModelPath())
        assertNull(preferences.getModelDisplayName())
    }

    @Test
    fun downloadModelReturnsFailureWhenSourceCannotBeOpened() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-failure-test").toFile()
        val previous = File(targetDirectory, "previous.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(byteArrayOf(9))
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(previous.absolutePath, "previous.litertlm")
        }
        val downloader = newDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openStream = { _ -> throw IOException("simulated open failure") }
        )

        val result = downloader.downloadModel()

        assertEquals(GemmaModelDownloadResult.Failed("Could not download Gemma model"), result)
        assertTrue(previous.exists())
        assertEquals(previous.absolutePath, preferences.getModelPath())
        assertEquals("previous.litertlm", preferences.getModelDisplayName())
        assertNull(File(targetDirectory, "gemma-subtitles.litertlm").takeIf { it.exists() })
    }

    @Test(expected = CancellationException::class)
    fun downloadModelPropagatesCancellationFromOpenStream() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-cancellation-test").toFile()
        val downloader = newDownloader(
            targetDirectory = targetDirectory,
            preferences = FakeGemmaSubtitlePreferences(),
            openStream = { _ -> throw CancellationException("cancelled") }
        )

        downloader.downloadModel()
    }

    @Test
    fun downloadModelCleansTempFileAndKeepsExistingStateWhenCopyIsCancelled() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-copy-cancellation-test").toFile()
        val existingBytes = byteArrayOf(20, 21, 22, 23)
        val existingModel = File(targetDirectory, "gemma-subtitles.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(existingBytes)
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(existingModel.absolutePath, "existing.litertlm")
        }
        val downloader = newDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openStream = { _ ->
                GemmaModelDownloadStream(
                    inputStream = CancellingAfterBytesInputStream(byteArrayOf(1, 2, 3)),
                    contentLengthBytes = 3L
                )
            }
        )

        try {
            downloader.downloadModel()
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancelled during copy", expected.message)
        }

        assertTrue(targetDirectory.listFiles { file -> file.extension == "tmp" }.orEmpty().isEmpty())
        assertArrayEquals(existingBytes, existingModel.readBytes())
        assertEquals(existingModel.absolutePath, preferences.getModelPath())
        assertEquals("existing.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun downloadModelObservesCoroutineCancellationDuringCopy() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-job-cancellation-test").toFile()
        val existingBytes = byteArrayOf(30, 31, 32, 33)
        val existingModel = File(targetDirectory, "gemma-subtitles.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(existingBytes)
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(existingModel.absolutePath, "existing.litertlm")
        }
        val newBytes = ByteArray(256 * 1024) { index -> index.toByte() }
        val downloader = newDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openStream = { _ ->
                GemmaModelDownloadStream(
                    inputStream = ByteArrayInputStream(newBytes),
                    contentLengthBytes = newBytes.size.toLong()
                )
            }
        )

        val deferred = async(start = CoroutineStart.LAZY) {
            val downloadJob = currentCoroutineContext()[Job] ?: error("Missing coroutine job")
            downloader.downloadModel { progress ->
                if (progress.bytesDownloaded > 0L) {
                    downloadJob.cancel(CancellationException("cancelled by test"))
                }
            }
        }

        deferred.start()
        try {
            deferred.await()
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancelled by test", expected.message)
        }

        assertTrue(targetDirectory.listFiles { file -> file.extension == "tmp" }.orEmpty().isEmpty())
        assertArrayEquals(existingBytes, existingModel.readBytes())
        assertEquals(existingModel.absolutePath, preferences.getModelPath())
        assertEquals("existing.litertlm", preferences.getModelDisplayName())
    }

    @Test
    fun downloadModelObservesCoroutineCancellationBeforeCommitAfterCopyCompletes() = runTest {
        val targetDirectory = Files.createTempDirectory("gemma-download-pre-commit-cancellation-test").toFile()
        val existingBytes = byteArrayOf(40, 41, 42, 43)
        val existingModel = File(targetDirectory, "gemma-subtitles.litertlm").apply {
            parentFile?.mkdirs()
            writeBytes(existingBytes)
        }
        val preferences = FakeGemmaSubtitlePreferences().apply {
            setModel(existingModel.absolutePath, "existing.litertlm")
        }
        val newBytes = byteArrayOf(1, 2, 3, 4)
        lateinit var downloadJob: Job
        val downloader = newDownloader(
            targetDirectory = targetDirectory,
            preferences = preferences,
            openStream = { _ ->
                GemmaModelDownloadStream(
                    inputStream = CancellingOnEofInputStream(newBytes) {
                        downloadJob.cancel(CancellationException("cancelled before commit"))
                    },
                    contentLengthBytes = newBytes.size.toLong()
                )
            }
        )

        val deferred = async(start = CoroutineStart.LAZY) {
            downloadJob = currentCoroutineContext()[Job] ?: error("Missing coroutine job")
            downloader.downloadModel()
        }

        deferred.start()
        try {
            deferred.await()
            fail("Expected CancellationException")
        } catch (expected: CancellationException) {
            assertEquals("cancelled before commit", expected.message)
        }

        assertTrue(targetDirectory.listFiles { file -> file.extension == "tmp" }.orEmpty().isEmpty())
        assertArrayEquals(existingBytes, existingModel.readBytes())
        assertEquals(existingModel.absolutePath, preferences.getModelPath())
        assertEquals("existing.litertlm", preferences.getModelDisplayName())
    }

    private fun newDownloader(
        targetDirectory: File,
        preferences: GemmaSubtitlePreferences,
        expectedSha256Hex: String = sha256Hex(ByteArray(0)),
        openStream: (URL) -> GemmaModelDownloadStream?
    ): GemmaModelDownloader = GemmaModelDownloader(
        targetDirectory = targetDirectory,
        preferences = preferences,
        source = GemmaModelDownloadSource(
            url = URL("https://example.test/gemma.litertlm"),
            displayName = "gemma-4-E2B-it.litertlm",
            expectedSha256Hex = expectedSha256Hex
        ),
        openStream = openStream
    )
}

private fun sha256Hex(bytes: ByteArray): String {
    val digest = MessageDigest.getInstance("SHA-256").digest(bytes)
    return digest.joinToString(separator = "") { byte -> "%02x".format(byte) }
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

private class CancellingOnEofInputStream(
    private val bytes: ByteArray,
    private val onEof: () -> Unit
) : InputStream() {
    private var offset = 0

    override fun read(): Int {
        if (offset < bytes.size) {
            return bytes[offset++].toInt() and 0xff
        }
        onEof()
        return -1
    }

    override fun read(buffer: ByteArray, off: Int, len: Int): Int {
        if (offset < bytes.size) {
            val count = minOf(len, bytes.size - offset)
            bytes.copyInto(buffer, off, offset, offset + count)
            offset += count
            return count
        }
        onEof()
        return -1
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
