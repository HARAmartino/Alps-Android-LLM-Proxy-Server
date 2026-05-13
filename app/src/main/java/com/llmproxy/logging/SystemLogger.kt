package com.llmproxy.logging

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/**
 * Writes structured system/debug log entries to a rolling daily log file.
 *
 * ### File layout
 * ```
 * <filesDir>/logs/system.log            ← current day's log
 * <filesDir>/logs/system.2025-05-12.log.gz  ← compressed archive (previous days)
 * ```
 *
 * ### Rotation logic
 * Before every write the logger checks whether the calendar date has changed
 * since the last write. If so, the current `system.log` is compressed into a
 * gzip archive named after *yesterday's* date, and a fresh `system.log` is
 * started. Archives older than [MAX_ARCHIVE_DAYS] (7) days are deleted.
 *
 * ### In-memory ring buffer
 * The last [MAX_RECENT_ERRORS] ERROR-level entries are kept in a bounded
 * in-memory list so the dashboard can surface them without hitting disk.
 *
 * All file I/O runs on [Dispatchers.IO] and is wrapped in try-catch to ensure
 * that log failures can never crash the proxy service.
 */
class SystemLogger(private val context: Context) {

    private val logDir: File by lazy {
        File(context.filesDir, "logs").also { it.mkdirs() }
    }
    private val currentLogFile: File by lazy { File(logDir, "system.log") }

    /** Tracks the calendar date of the last write for daily rotation. */
    @Volatile
    private var lastWriteDate: LocalDate? = null

    /** Mutex to serialise concurrent writes and rotations. */
    private val writeMutex = Mutex()

    /** Bounded ring buffer of the most recent ERROR-level entries. */
    private val recentErrors = ArrayDeque<SystemLogEntry>(MAX_RECENT_ERRORS + 1)
    private val recentErrorsMutex = Mutex()

    // ── Public API ───────────────────────────────────────────────────────────

    suspend fun debug(tag: String, message: String) =
        log(SystemLogEntry(nowIso(), "DEBUG", tag, message))

    suspend fun info(tag: String, message: String) =
        log(SystemLogEntry(nowIso(), "INFO", tag, message))

    suspend fun warn(tag: String, message: String, throwable: Throwable? = null) =
        log(SystemLogEntry(nowIso(), "WARN", tag, message, throwable?.stackTraceToString()))

    suspend fun error(tag: String, message: String, throwable: Throwable? = null) =
        log(SystemLogEntry(nowIso(), "ERROR", tag, message, throwable?.stackTraceToString()))

    /** Writes a [SystemLogEntry] to disk and updates the in-memory error buffer. */
    suspend fun log(entry: SystemLogEntry) = withContext(Dispatchers.IO) {
        // Update in-memory recent-errors ring buffer for ERROR level.
        if (entry.level == "ERROR") {
            recentErrorsMutex.withLock {
                recentErrors.addLast(entry)
                // Evict oldest when buffer is full.
                while (recentErrors.size > MAX_RECENT_ERRORS) {
                    recentErrors.removeFirst()
                }
            }
        }

        writeMutex.withLock {
            try {
                val today = LocalDate.now(ZoneOffset.UTC)
                // Rotate if the date has changed since the last write.
                if (lastWriteDate != null && lastWriteDate != today) {
                    rotateDailyLog(lastWriteDate!!)
                    deleteOldArchives()
                }
                lastWriteDate = today

                currentLogFile.appendText(formatEntry(entry), Charsets.UTF_8)
            } catch (e: Exception) {
                android.util.Log.w(TAG, "Failed to write system log entry: ${e.message}")
            }
        }
    }

    /** Returns a snapshot of the last [MAX_RECENT_ERRORS] ERROR-level entries. */
    suspend fun recentErrors(): List<SystemLogEntry> =
        recentErrorsMutex.withLock { recentErrors.toList() }

    /**
     * Reads the current log file, redacts sensitive data, and returns the
     * content as a [String] suitable for export.
     */
    suspend fun readAllRedacted(): String = withContext(Dispatchers.IO) {
        try {
            if (!currentLogFile.exists()) return@withContext ""
            currentLogFile.readText(Charsets.UTF_8).redactSensitiveData()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to read system log: ${e.message}")
            ""
        }
    }

    /** Returns an [Intent] that shares the current system log via ACTION_SEND. */
    fun createExportIntent(): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            currentLogFile.also { currentLogFile.parentFile?.mkdirs() },
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "LLM Proxy system logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Private helpers ──────────────────────────────────────────────────────

    /**
     * Archives the current `system.log` into a gzip file named after [date]
     * and truncates `system.log` to start fresh.
     *
     * Archive name format: `system.YYYY-MM-DD.log.gz`
     */
    private fun rotateDailyLog(date: LocalDate) {
        if (!currentLogFile.exists() || currentLogFile.length() == 0L) return

        val archiveName = "system.${DATE_FORMATTER.format(date)}.log.gz"
        val archiveFile = File(logDir, archiveName)

        try {
            FileInputStream(currentLogFile).use { fis ->
                GZIPOutputStream(FileOutputStream(archiveFile)).use { gzos ->
                    fis.copyTo(gzos)
                }
            }
            // Truncate the live log file after successful archive creation.
            currentLogFile.writeText("")
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Daily rotation failed: ${e.message}")
            // Leave the current log intact if archiving failed.
            archiveFile.takeIf { it.exists() && it.length() == 0L }?.delete()
        }
    }

    /**
     * Removes gzip archives older than [MAX_ARCHIVE_DAYS] days, keeping only
     * the most recent archives. Sorted by file name (which sorts by date due to
     * the `YYYY-MM-DD` naming convention).
     */
    private fun deleteOldArchives() {
        try {
            val archives = logDir.listFiles { f ->
                f.name.startsWith("system.") && f.name.endsWith(".log.gz")
            }?.sortedBy { it.name } ?: return

            // Delete all but the newest MAX_ARCHIVE_DAYS files.
            if (archives.size > MAX_ARCHIVE_DAYS) {
                archives.dropLast(MAX_ARCHIVE_DAYS).forEach { it.delete() }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Archive cleanup failed: ${e.message}")
        }
    }

    private fun formatEntry(entry: SystemLogEntry): String = buildString {
        append("[${entry.timestamp}] [${entry.level}] [${entry.tag}] ${entry.message}")
        if (!entry.stacktrace.isNullOrBlank()) {
            append("\n")
            append(entry.stacktrace)
        }
        append("\n")
    }

    private fun nowIso(): String =
        java.time.Instant.now().toString() // e.g. 2025-05-13T18:15:22.164Z

    companion object {
        private const val TAG = "SystemLogger"

        /** Number of archives to retain (one per day). */
        const val MAX_ARCHIVE_DAYS = 7

        /** Maximum ERROR entries kept in the in-memory ring buffer. */
        const val MAX_RECENT_ERRORS = 50

        private val DATE_FORMATTER: DateTimeFormatter =
            DateTimeFormatter.ofPattern("yyyy-MM-dd")
    }
}
