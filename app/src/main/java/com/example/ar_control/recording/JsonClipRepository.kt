package com.example.ar_control.recording

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

class JsonClipRepository(
    private val catalogFile: File
) : ClipRepository {

    private val mutex = Mutex()

    /**
     * Returns the visible clip list for the catalog.
     *
     * Malformed or truncated catalog content is preserved on disk and surfaced to callers as an
     * empty visible list, so this repository does not silently repair or flatten bad input.
     */
    override suspend fun load(): List<RecordedClip> = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val parsed = readAll()) {
                JsonClipCatalogParseResult.Empty -> emptyList()
                is JsonClipCatalogParseResult.Malformed -> emptyList()
                is JsonClipCatalogParseResult.Success -> {
                    val cleaned = parsed.clips
                        .filter { File(it.filePath).exists() }
                        .sortedByDescending { it.createdAtEpochMillis }
                    if (cleaned != parsed.clips) {
                        writeAll(cleaned)
                    }
                    cleaned
                }
            }
        }
    }

    override suspend fun insert(clip: RecordedClip) = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val parsed = readAll()) {
                JsonClipCatalogParseResult.Empty -> writeAll(normalize(listOf(clip)))
                is JsonClipCatalogParseResult.Malformed -> return@withLock
                is JsonClipCatalogParseResult.Success -> {
                    val updated = normalize(parsed.clips + clip)
                    writeAll(updated)
                }
            }
        }
    }

    override suspend fun delete(clipId: String): Boolean = withContext(Dispatchers.IO) {
        mutex.withLock {
            when (val parsed = readAll()) {
                JsonClipCatalogParseResult.Empty -> false
                is JsonClipCatalogParseResult.Malformed -> false
                is JsonClipCatalogParseResult.Success -> {
                    val existing = parsed.clips
                    val target = existing.firstOrNull { it.id == clipId } ?: return@withLock false
                    val targetFile = File(target.filePath)
                    if (targetFile.exists()) {
                        runCatching { targetFile.delete() }
                    }
                    if (targetFile.exists()) return@withLock false

                    val updated = normalize(existing.filterNot { it.id == clipId })
                    writeAll(updated)
                    true
                }
            }
        }
    }

    private fun readAll(): JsonClipCatalogParseResult {
        if (!catalogFile.exists()) return JsonClipCatalogParseResult.Empty
        return JsonClipCodec.decodeCatalog(catalogFile.readText(Charsets.UTF_8))
    }

    private fun writeAll(clips: List<RecordedClip>) {
        val targetPath = catalogFile.toPath()
        val directory = targetPath.parent ?: targetPath.toAbsolutePath().parent ?: return
        Files.createDirectories(directory)

        val tempFile = Files.createTempFile(directory, targetPath.fileName.toString(), ".tmp")
        try {
            Files.write(
                tempFile,
                JsonClipCodec.encodeCatalog(clips).toByteArray(StandardCharsets.UTF_8)
            )
            try {
                Files.move(
                    tempFile,
                    targetPath,
                    StandardCopyOption.REPLACE_EXISTING,
                    StandardCopyOption.ATOMIC_MOVE
                )
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tempFile, targetPath, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tempFile)
        }
    }

    private fun normalize(clips: List<RecordedClip>): List<RecordedClip> {
        return clips.sortedByDescending { it.createdAtEpochMillis }
    }
}
