package com.example.ar_control.gemma

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

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

            return@withContext GemmaModelImportResult.Imported(targetPath, safeDisplayName)
        } catch (error: Exception) {
            if (error is CancellationException) throw error
            tempFile?.delete()
            return@withContext GemmaModelImportResult.Failed(COULD_NOT_IMPORT_REASON)
        } finally {
            runCatching { source.close() }
        }
    }

    private fun String?.safeDisplayName(): String {
        val trimmed = this?.trim().orEmpty()
        val basename = trimmed.substringAfterLast('/').substringAfterLast('\\')
        return basename.takeIf { it.isNotBlank() } ?: MODEL_FILE_NAME
    }

    private companion object {
        const val MODEL_DIRECTORY_NAME = "models"
        const val MODEL_FILE_NAME = "gemma-subtitles.litertlm"
        const val COULD_NOT_OPEN_REASON = "Could not open selected Gemma model"
        const val COULD_NOT_IMPORT_REASON = "Could not import selected Gemma model"
    }
}
