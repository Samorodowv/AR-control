package com.example.ar_control.gemma

import android.content.Context
import android.net.Uri
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
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
    val displayName: String
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
        onProgress: (GemmaModelDownloadProgress) -> Unit = {}
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
            copyWithProgress(stream.inputStream, tempFile, stream.contentLengthBytes, onProgress)
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

sealed interface GemmaModelImportResult {
    data class Imported(val path: String, val displayName: String?) : GemmaModelImportResult
    data class Failed(val reason: String) : GemmaModelImportResult
}

class GemmaModelImporter internal constructor(
    private val targetDirectory: File,
    private val preferences: GemmaSubtitlePreferences,
    private val openInputStream: (Uri) -> InputStream?,
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO
) {

    constructor(
        context: Context,
        preferences: GemmaSubtitlePreferences
    ) : this(
        targetDirectory = File(context.applicationContext.filesDir, MODEL_DIRECTORY_NAME),
        preferences = preferences,
        openInputStream = { uri -> context.applicationContext.contentResolver.openInputStream(uri) }
    )

    suspend fun importModel(uri: Uri, displayName: String?): GemmaModelImportResult = withContext(ioDispatcher) {
        val source = try {
            openInputStream(uri)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            null
        } ?: return@withContext GemmaModelImportResult.Failed(COULD_NOT_OPEN_REASON)

        var tempFile: File? = null
        try {
            targetDirectory.mkdirs()
            val targetFile = File(targetDirectory, MODEL_FILE_NAME)
            tempFile = File.createTempFile(MODEL_FILE_NAME, ".tmp", targetDirectory)
            tempFile.outputStream().use { output ->
                source.copyTo(output)
            }
            Files.move(
                tempFile.toPath(),
                targetFile.toPath(),
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING
            )

            val targetPath = targetFile.absolutePath
            val safeDisplayName = displayName.safeDisplayName()
            preferences.setModel(targetPath, safeDisplayName)

            GemmaModelImportResult.Imported(targetPath, safeDisplayName)
        } catch (error: Exception) {
            tempFile?.delete()
            if (error is CancellationException) throw error
            GemmaModelImportResult.Failed(COULD_NOT_IMPORT_REASON)
        } finally {
            runCatching { source.close() }
        }
    }

    private fun String?.safeDisplayName(): String {
        val trimmed = this?.trim().orEmpty()
        val basename = trimmed.substringAfterLast('/').substringAfterLast('\\')
        return basename.takeIf { it.isNotBlank() } ?: MODEL_FILE_NAME
    }
}

private fun openHttpsStream(url: URL): GemmaModelDownloadStream {
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

private fun copyWithProgress(
    source: InputStream,
    targetFile: File,
    totalBytes: Long?,
    onProgress: (GemmaModelDownloadProgress) -> Unit
) {
    var bytesDownloaded = 0L
    onProgress(GemmaModelDownloadProgress(bytesDownloaded, totalBytes))
    targetFile.outputStream().use { output ->
        val buffer = ByteArray(COPY_BUFFER_SIZE_BYTES)
        while (true) {
            val read = source.read(buffer)
            if (read == -1) {
                break
            }
            output.write(buffer, 0, read)
            bytesDownloaded += read.toLong()
            onProgress(GemmaModelDownloadProgress(bytesDownloaded, totalBytes))
        }
    }
}

private const val MODEL_DIRECTORY_NAME = "models"
private const val MODEL_FILE_NAME = "gemma-subtitles.litertlm"
private const val MODEL_DISPLAY_NAME = "gemma-4-E2B-it.litertlm"
private const val MODEL_URL =
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/main/gemma-4-E2B-it.litertlm?download=true"
private const val COULD_NOT_DOWNLOAD_REASON = "Could not download Gemma model"
private const val COULD_NOT_OPEN_REASON = "Could not open selected Gemma model"
private const val COULD_NOT_IMPORT_REASON = "Could not import selected Gemma model"
private const val CONNECT_TIMEOUT_MILLIS = 15_000
private const val READ_TIMEOUT_MILLIS = 30_000
private const val COPY_BUFFER_SIZE_BYTES = 128 * 1024
private val DEFAULT_SOURCE = GemmaModelDownloadSource(URL(MODEL_URL), MODEL_DISPLAY_NAME)
