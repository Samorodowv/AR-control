package com.example.ar_control.recording

import java.io.File
import java.nio.file.Files
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JsonClipRepositoryTest {

    @Test
    fun insertKeepsNewestFirstOrdering() = runTest {
        val dir = Files.createTempDirectory("clip_repo_order").toFile()
        val firstFile = File(dir, "older.mp4").apply { writeBytes(byteArrayOf(1)) }
        val secondFile = File(dir, "newer.mp4").apply { writeBytes(byteArrayOf(2)) }
        val repository = JsonClipRepository(File(dir, "clips.json"))

        repository.insert(
            RecordedClip(
                id = "older",
                filePath = firstFile.absolutePath,
                createdAtEpochMillis = 1_000L,
                durationMillis = 100L,
                width = 1920,
                height = 1080,
                fileSizeBytes = firstFile.length(),
                mimeType = "video/mp4"
            )
        )
        repository.insert(
            RecordedClip(
                id = "newer",
                filePath = secondFile.absolutePath,
                createdAtEpochMillis = 2_000L,
                durationMillis = 100L,
                width = 1920,
                height = 1080,
                fileSizeBytes = secondFile.length(),
                mimeType = "video/mp4"
            )
        )

        assertEquals(listOf("newer", "older"), repository.load().map { it.id })
    }

    @Test
    fun loadRemovesMissingFilesAndWritesCleanedCatalogBack() = runTest {
        val dir = Files.createTempDirectory("clip_repo_missing").toFile()
        val existingFile = File(dir, "existing.mp4").apply { writeBytes(byteArrayOf(3)) }
        val missingFile = File(dir, "missing.mp4")
        val catalogFile = File(dir, "clips.json")
        val repository = JsonClipRepository(catalogFile)

        repository.insert(
            RecordedClip(
                id = "existing",
                filePath = existingFile.absolutePath,
                createdAtEpochMillis = 3_000L,
                durationMillis = 100L,
                width = 1920,
                height = 1080,
                fileSizeBytes = existingFile.length(),
                mimeType = "video/mp4"
            )
        )
        repository.insert(
            RecordedClip(
                id = "missing",
                filePath = missingFile.absolutePath,
                createdAtEpochMillis = 4_000L,
                durationMillis = 100L,
                width = 1920,
                height = 1080,
                fileSizeBytes = 0L,
                mimeType = "video/mp4"
            )
        )

        val loaded = repository.load()

        assertEquals(listOf("existing"), loaded.map { it.id })
        assertTrue(catalogFile.exists())
        assertTrue(catalogFile.readText().contains("\"id\":\"existing\""))
        assertFalse(catalogFile.readText().contains("\"id\":\"missing\""))
    }

    @Test
    fun deleteRemovesCatalogEntryAndReturnsTrueWhenFileAlreadyGone() = runTest {
        val dir = Files.createTempDirectory("clip_repo_delete").toFile()
        val file = File(dir, "clip.mp4").apply { writeBytes(byteArrayOf(4)) }
        val catalogFile = File(dir, "clips.json")
        val repository = JsonClipRepository(catalogFile)

        repository.insert(
            RecordedClip(
                id = "clip",
                filePath = file.absolutePath,
                createdAtEpochMillis = 5_000L,
                durationMillis = 100L,
                width = 1920,
                height = 1080,
                fileSizeBytes = file.length(),
                mimeType = "video/mp4"
            )
        )
        assertTrue(file.delete())

        assertTrue(repository.delete("clip"))
        assertEquals(emptyList<String>(), repository.load().map { it.id })
    }

    @Test
    fun deleteRetainsCatalogEntryWhenFileDeletionFails() = runTest {
        val dir = Files.createTempDirectory("clip_repo_delete_fail").toFile()
        val undeletablePath = File(dir, "undeletable").apply { mkdirs() }
        File(undeletablePath, "child.txt").writeText("data")
        val catalogFile = File(dir, "clips.json")
        val repository = JsonClipRepository(catalogFile)

        repository.insert(
            RecordedClip(
                id = "blocked",
                filePath = undeletablePath.absolutePath,
                createdAtEpochMillis = 6_000L,
                durationMillis = 100L,
                width = 1920,
                height = 1080,
                fileSizeBytes = 0L,
                mimeType = "video/mp4"
            )
        )

        assertFalse(repository.delete("blocked"))
        assertEquals(listOf("blocked"), repository.load().map { it.id })
        assertTrue(undeletablePath.exists())
    }

    @Test
    fun loadWithMalformedCatalogPreservesFileContents() = runTest {
        val dir = Files.createTempDirectory("clip_repo_malformed").toFile()
        val malformed = "{not valid json"
        val catalogFile = File(dir, "clips.json").apply { writeText(malformed) }
        val repository = JsonClipRepository(catalogFile)

        assertTrue(repository.load().isEmpty())
        assertEquals(malformed, catalogFile.readText())
    }
}
