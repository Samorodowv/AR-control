package com.example.ar_control.diagnostics

import java.io.File
import java.time.Instant
import kotlin.io.path.createTempDirectory
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistentSessionLogTest {

    @Test
    fun snapshot_restoresPersistedEntriesAcrossInstances() {
        val timestamps = ArrayDeque(
            listOf(
                Instant.parse("2026-04-18T10:00:00Z"),
                Instant.parse("2026-04-18T10:00:01Z")
            )
        )
        val directory = createTempDirectory(prefix = "persistent_session_log").toFile()
        val logFile = File(directory, "session-log.json")

        val firstLog = PersistentSessionLog(
            file = logFile,
            capacity = 10,
            now = { timestamps.removeFirst() }
        )
        firstLog.record("PreviewViewModel", "first")
        firstLog.record("MainActivity", "second")

        val restoredLog = PersistentSessionLog(
            file = logFile,
            capacity = 10,
            now = { Instant.parse("2026-04-18T10:00:02Z") }
        )

        assertEquals(
            listOf("first", "second"),
            restoredLog.snapshot().map(SessionLogEntry::message)
        )
        assertEquals(
            listOf("PreviewViewModel", "MainActivity"),
            restoredLog.snapshot().map(SessionLogEntry::source)
        )
    }

    @Test
    fun uncaughtException_logsFatalCrashAndDelegates() {
        val directory = createTempDirectory(prefix = "persistent_session_log_crash").toFile()
        val logFile = File(directory, "session-log.json")
        val sessionLog = PersistentSessionLog(
            file = logFile,
            capacity = 10,
            now = { Instant.parse("2026-04-18T11:00:00Z") }
        )
        val delegated = mutableListOf<Throwable>()
        val handler = CrashLoggingExceptionHandler(
            sessionLog = sessionLog,
            delegate = Thread.UncaughtExceptionHandler { _, throwable ->
                delegated += throwable
            }
        )
        val crash = IllegalStateException("boom")

        handler.uncaughtException(Thread.currentThread(), crash)

        val snapshot = sessionLog.snapshot()
        assertEquals(1, snapshot.size)
        assertEquals("FatalCrash", snapshot.single().source)
        assertTrue(snapshot.single().message.contains("IllegalStateException"))
        assertTrue(snapshot.single().message.contains("boom"))
        assertEquals(listOf(crash), delegated)
    }
}
