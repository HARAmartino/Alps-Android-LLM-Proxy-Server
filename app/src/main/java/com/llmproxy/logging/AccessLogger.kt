package com.llmproxy.logging

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.RandomAccessFile

/**
 * Writes HTTP access log entries in JSONL format (one JSON object per line).
 *
 * ### File location
 * `<filesDir>/logs/access.jsonl`
 *
 * ### Rotation
 * After each successful write the file size is checked. When it exceeds
 * [MAX_FILE_BYTES] (10 MB) the *oldest* entries are removed: the file is
 * read in full, the first (oldest) half of lines is discarded, and the
 * remaining lines are written back. No compression is applied at this stage.
 *
 * All file I/O runs on [Dispatchers.IO] and is wrapped in try-catch so that
 * a log failure never crashes the proxy service.
 */
class AccessLogger(private val context: Context) {

    private val logFile: File by lazy {
        File(context.filesDir, "logs/access.jsonl").also { it.parentFile?.mkdirs() }
    }

    /**
     * Serialises [entry] to a single JSON line and appends it to the log file.
     * Triggers rotation if the file exceeds [MAX_FILE_BYTES].
     */
    suspend fun log(entry: AccessLogEntry) = withContext(Dispatchers.IO) {
        try {
            val line = entryToJson(entry) + "\n"
            logFile.appendText(line, Charsets.UTF_8)

            // Rotation check: drop oldest lines when file exceeds the size cap.
            if (logFile.length() > MAX_FILE_BYTES) {
                rotateByDroppingOldestHalf()
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to write access log entry: ${e.message}")
        }
    }

    /**
     * Reads the entire log file content, redacts sensitive fields, and returns
     * it as a [String] suitable for sharing.
     */
    suspend fun readAllRedacted(): String = withContext(Dispatchers.IO) {
        try {
            if (!logFile.exists()) return@withContext ""
            logFile.readText(Charsets.UTF_8).redactSensitiveData()
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Failed to read access log: ${e.message}")
            ""
        }
    }

    /** Returns an [Intent] that shares the access log file via ACTION_SEND. */
    fun createExportIntent(): Intent {
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            logFile.also { it.parentFile?.mkdirs() },
        )
        return Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "LLM Proxy access logs")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }

    // ── Private helpers ─────────────────────────────────────────────────────

    /**
     * Removes the oldest 50 % of log lines to reclaim space.
     *
     * We use a two-pass approach:
     *   1. Read all lines into memory.
     *   2. Keep only the newest half.
     *   3. Overwrite the file with the kept lines.
     *
     * This is intentionally simple (no circular buffer) because the file is
     * already capped at 10 MB — a reasonable in-memory footprint.
     */
    private fun rotateByDroppingOldestHalf() {
        try {
            val lines = logFile.readLines(Charsets.UTF_8)
            if (lines.size < 2) return

            // Keep the newest half; discard the oldest half.
            val keepFrom = lines.size / 2
            val kept = lines.subList(keepFrom, lines.size)

            RandomAccessFile(logFile, "rw").use { raf ->
                raf.setLength(0)
                raf.seek(0)
                kept.forEach { line ->
                    raf.write((line + "\n").toByteArray(Charsets.UTF_8))
                }
            }
        } catch (e: Exception) {
            android.util.Log.w(TAG, "Rotation failed, truncating file: ${e.message}")
            // Last resort: just clear the file to unblock writes.
            try { logFile.writeText("") } catch (_: Exception) {}
        }
    }

    private fun entryToJson(entry: AccessLogEntry): String =
        JSONObject()
            .put("timestamp", entry.timestamp)
            .put("client_ip", entry.clientIp)
            .put("request_path", entry.requestPath)
            .put("status_code", entry.statusCode)
            .put("latency_ms", entry.latencyMs)
            .put("upstream_host", entry.upstreamHost)
            .toString()

    companion object {
        private const val TAG = "AccessLogger"

        /** Maximum log file size before rotation is triggered (10 MB). */
        const val MAX_FILE_BYTES = 10L * 1024 * 1024
    }
}
