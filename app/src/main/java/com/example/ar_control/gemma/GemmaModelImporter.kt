package com.example.ar_control.gemma

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.InputStream

sealed interface GemmaModelImportResult {
    data class Imported(val path: String, val displayName: String?) : GemmaModelImportResult
    data class Failed(val reason: String) : GemmaModelImportResult
}

class GemmaModelImporter internal constructor(
    private val targetDirectory: File,
    private val preferences: GemmaSubtitlePreferences,
    private val openInputStream: (Uri) -> InputStream?
) {

    constructor(
        context: Context,
        preferences: GemmaSubtitlePreferences
    ) : this(
        targetDirectory = File(context.applicationContext.filesDir, MODEL_DIRECTORY_NAME),
        preferences = preferences,
        openInputStream = { uri -> context.applicationContext.contentResolver.openInputStream(uri) }
    )

    suspend fun importModel(uri: Uri, displayName: String?): GemmaModelImportResult {
        val source = try {
            openInputStream(uri)
        } catch (_: Exception) {
            null
        } ?: return GemmaModelImportResult.Failed(COULD_NOT_OPEN_REASON)

        targetDirectory.mkdirs()
        val targetFile = File(targetDirectory, MODEL_FILE_NAME)
        val previousPath = preferences.getModelPath()
        source.use { input ->
            targetFile.outputStream().use { output ->
                input.copyTo(output)
            }
        }

        val targetPath = targetFile.absolutePath
        val safeDisplayName = displayName.safeDisplayName()
        preferences.setModel(targetPath, safeDisplayName)

        if (previousPath != null && previousPath != targetPath) {
            File(previousPath).delete()
        }

        return GemmaModelImportResult.Imported(targetPath, safeDisplayName)
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
    }
}
