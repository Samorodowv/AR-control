package com.example.ar_control.diagnostics

import java.io.File
import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.util.ArrayDeque
import java.util.Base64

data class SessionLogEntry(
    val timestamp: Instant,
    val source: String,
    val message: String
)

interface SessionLog {
    fun record(source: String, message: String)

    fun snapshot(): List<SessionLogEntry>
}

object NoOpSessionLog : SessionLog {
    override fun record(source: String, message: String) = Unit

    override fun snapshot(): List<SessionLogEntry> = emptyList()
}

class InMemorySessionLog(
    private val capacity: Int = 200,
    private val now: () -> Instant = { Instant.now() }
) : SessionLog {
    private val lock = Any()
    private val entries = ArrayDeque<SessionLogEntry>()

    init {
        require(capacity > 0) { "capacity must be greater than zero" }
    }

    override fun record(source: String, message: String) {
        synchronized(lock) {
            if (entries.size == capacity) {
                entries.removeFirst()
            }
            entries.addLast(
                SessionLogEntry(
                    timestamp = now(),
                    source = source,
                    message = message
                )
            )
        }
    }

    override fun snapshot(): List<SessionLogEntry> = synchronized(lock) {
        entries.toList()
    }
}

class PersistentSessionLog(
    private val file: File,
    private val capacity: Int = 400,
    private val now: () -> Instant = { Instant.now() }
) : SessionLog {

    private val lock = Any()
    private val entries = ArrayDeque<SessionLogEntry>()

    init {
        require(capacity > 0) { "capacity must be greater than zero" }
        loadFromDisk()
    }

    override fun record(source: String, message: String) {
        synchronized(lock) {
            appendEntry(
                SessionLogEntry(
                    timestamp = now(),
                    source = source,
                    message = message
                )
            )
            persistLocked()
        }
    }

    override fun snapshot(): List<SessionLogEntry> = synchronized(lock) {
        entries.toList()
    }

    private fun loadFromDisk() {
        synchronized(lock) {
            if (!file.exists()) {
                return
            }
            runCatching {
                file.readLines(Charsets.UTF_8)
                    .mapNotNull(::decodeLineOrNull)
                    .forEach(::appendEntry)
            }
        }
    }

    private fun appendEntry(entry: SessionLogEntry) {
        if (entries.size == capacity) {
            entries.removeFirst()
        }
        entries.addLast(entry)
    }

    private fun persistLocked() {
        val targetPath = file.toPath()
        val directory = targetPath.parent ?: targetPath.toAbsolutePath().parent ?: return
        Files.createDirectories(directory)

        val tempFile = Files.createTempFile(directory, targetPath.fileName.toString(), ".tmp")
        try {
            Files.write(
                tempFile,
                entries.joinToString(separator = "\n") { entry -> encodeLine(entry) }
                    .toByteArray(StandardCharsets.UTF_8)
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

    private fun encodeLine(entry: SessionLogEntry): String {
        return listOf(
            entry.timestamp.toString(),
            encodeField(entry.source),
            encodeField(entry.message)
        ).joinToString(separator = "\t")
    }

    private fun decodeLineOrNull(rawLine: String): SessionLogEntry? {
        val line = rawLine.trim()
        if (line.isEmpty()) {
            return null
        }
        val parts = line.split('\t', limit = 3)
        if (parts.size != 3) {
            return null
        }
        return runCatching {
            SessionLogEntry(
                timestamp = Instant.parse(parts[0]),
                source = decodeField(parts[1]),
                message = decodeField(parts[2])
            )
        }.getOrNull()
    }

    private fun encodeField(value: String): String {
        return Base64.getEncoder().encodeToString(value.toByteArray(StandardCharsets.UTF_8))
    }

    private fun decodeField(value: String): String {
        return String(Base64.getDecoder().decode(value), StandardCharsets.UTF_8)
    }
}

class CrashLoggingExceptionHandler(
    private val sessionLog: SessionLog,
    private val fatalCrashMarkerFile: File? = null,
    private val delegate: Thread.UncaughtExceptionHandler?
) : Thread.UncaughtExceptionHandler {

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        runCatching {
            val report = buildString {
                append("Thread=")
                append(thread.name)
                append('\n')
                append(throwable.stackTraceToString())
            }
            sessionLog.record(
                source = "FatalCrash",
                message = report
            )
            fatalCrashMarkerFile?.let { writeFatalCrashMarker(it, report) }
        }
        delegate?.uncaughtException(thread, throwable)
    }

    private fun writeFatalCrashMarker(targetFile: File, report: String) {
        val targetPath = targetFile.toPath()
        val directory = targetPath.parent ?: targetPath.toAbsolutePath().parent ?: return
        Files.createDirectories(directory)
        val tempFile = Files.createTempFile(directory, targetPath.fileName.toString(), ".tmp")
        try {
            Files.write(tempFile, report.toByteArray(StandardCharsets.UTF_8))
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
}
