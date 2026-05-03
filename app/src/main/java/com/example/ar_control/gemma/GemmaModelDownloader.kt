package com.example.ar_control.gemma

import android.content.Context
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.security.MessageDigest
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext

data class GemmaModelDownloadProgress(
    val bytesDownloaded: Long,
    val totalBytes: Long?
)

sealed interface GemmaModelDownloadResult {
    data class Downloaded(val path: String, val displayName: String) : GemmaModelDownloadResult
    data class Failed(val reason: String) : GemmaModelDownloadResult
}

internal data class GemmaModelDownloadSource(
    val url: URL,
    val displayName: String,
    val expectedSha256Hex: String
)

internal class GemmaModelDownloadStream(
    val inputStream: InputStream,
    val contentLengthBytes: Long?,
    private val closeConnection: () -> Unit = {}
) : Closeable {
    override fun close() {
        runCatching { inputStream.close() }
        closeConnection()
    }
}

class GemmaModelDownloader internal constructor(
    private val targetDirectory: File,
    private val preferences: GemmaSubtitlePreferences,
    private val source: GemmaModelDownloadSource = DEFAULT_SOURCE,
    private val openStream: (URL) -> GemmaModelDownloadStream?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {
    constructor(context: Context, preferences: GemmaSubtitlePreferences) : this(
        targetDirectory = File(context.applicationContext.filesDir, MODEL_DIRECTORY_NAME),
        preferences = preferences,
        openStream = ::openHttpsStream
    )

    suspend fun downloadModel(
        onProgress: suspend (GemmaModelDownloadProgress) -> Unit = {}
    ): GemmaModelDownloadResult = withContext(ioDispatcher) {
        val stream = try {
            openStream(source.url)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } ?: return@withContext GemmaModelDownloadResult.Failed(COULD_NOT_DOWNLOAD_REASON)

        var tempFile: File? = null
        try {
            targetDirectory.mkdirs()
            val targetFile = File(targetDirectory, MODEL_FILE_NAME)
            tempFile = File.createTempFile(MODEL_FILE_NAME, ".tmp", targetDirectory)
            val actualSha256Hex = copyWithProgress(stream.inputStream, tempFile, stream.contentLengthBytes, onProgress)
            currentCoroutineContext().ensureActive()
            if (!actualSha256Hex.equals(source.expectedSha256Hex, ignoreCase = true)) {
                throw IOException("Gemma model checksum mismatch")
            }
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )
            val targetPath = targetFile.absolutePath
            preferences.setModel(targetPath, source.displayName)
            GemmaModelDownloadResult.Downloaded(targetPath, source.displayName)
        } catch (error: Exception) {
            tempFile?.delete()
            if (error is CancellationException) throw error
            GemmaModelDownloadResult.Failed(COULD_NOT_DOWNLOAD_REASON)
        } finally {
            stream.close()
        }
    }
}

private fun openHttpsStream(url: URL): GemmaModelDownloadStream {
    if (!url.protocol.equals("https", ignoreCase = true)) {
        throw IOException("Gemma model download URL must use HTTPS")
    }
    val connection = url.openConnection()
    connection.connectTimeout = CONNECT_TIMEOUT_MILLIS
    connection.readTimeout = READ_TIMEOUT_MILLIS
    if (connection is HttpURLConnection) {
        connection.instanceFollowRedirects = true
        val responseCode = connection.responseCode
        if (responseCode !in 200..299) {
            connection.disconnect()
            throw IOException("Unexpected HTTP $responseCode")
        }
    }
    return GemmaModelDownloadStream(
        inputStream = connection.inputStream,
        contentLengthBytes = connection.contentLengthLong.takeIf { it > 0L },
        closeConnection = {
            if (connection is HttpURLConnection) {
                connection.disconnect()
            }
        }
    )
}

private suspend fun copyWithProgress(
    source: InputStream,
    targetFile: File,
    totalBytes: Long?,
    onProgress: suspend (GemmaModelDownloadProgress) -> Unit
): String {
    val digest = MessageDigest.getInstance("SHA-256")
    var bytesDownloaded = 0L
    onProgress(GemmaModelDownloadProgress(bytesDownloaded, totalBytes))
    currentCoroutineContext().ensureActive()
    targetFile.outputStream().use { output ->
        val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
        while (true) {
            currentCoroutineContext().ensureActive()
            val read = source.read(buffer)
            if (read == -1) {
                break
            }
            output.write(buffer, 0, read)
            digest.update(buffer, 0, read)
            bytesDownloaded += read.toLong()
            onProgress(GemmaModelDownloadProgress(bytesDownloaded, totalBytes))
            currentCoroutineContext().ensureActive()
        }
    }
    currentCoroutineContext().ensureActive()
    return digest.digest().toHexString()
}

private fun ByteArray.toHexString(): String =
    joinToString(separator = "") { byte -> "%02x".format(byte) }

private const val MODEL_DIRECTORY_NAME = "models"
private const val MODEL_FILE_NAME = "gemma-subtitles.litertlm"
private const val MODEL_DISPLAY_NAME = "gemma-4-E2B-it.litertlm"
private const val MODEL_URL =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/7022fb75cac85d830562b14e8b583bdb7f8cb322/gemma-4-E2B-it.litertlm?download=true"
private const val MODEL_SHA256_HEX =
    "ab7838cdfc8f77e54d8ca45eadceb20452d9f01e4bfade03e5dce27911b27e42"
private const val COULD_NOT_DOWNLOAD_REASON = "Could not download Gemma model"
private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val COPY_BUFFER_SIZE_BYTES = 128 * 1024
private val DEFAULT_SOURCE = GemmaModelDownloadSource(URL(MODEL_URL), MODEL_DISPLAY_NAME, MODEL_SHA256_HEX)
