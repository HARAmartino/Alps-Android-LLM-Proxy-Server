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
     * Instead of reading all lines into memory, we seek to the midpoint byte offset
     * and scan forward to the next newline boundary, then copy only the retained
     * tail of the file back to the beginning. This keeps peak memory usage at ~5 MB
     * rather than the full 10 MB file size.
     */
    private fun rotateByDroppingOldestHalf() {
        if (!logFile.exists() || logFile.length() < 2) return

        try {
            val fileLength = logFile.length()
            val midpoint = fileLength / 2

            // Find the byte offset of the first newline at or after the midpoint.
            val keepFrom: Long = RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(midpoint)
                // Scan forward to the end of the current line (find the '\n').
                var pos = midpoint
                while (pos < fileLength) {
                    val byte = raf.read()
                    if (byte == -1) break
                    pos++
                    if (byte.toChar() == '\n') break
                }
                pos
            }

            if (keepFrom >= fileLength) {
                // The midpoint landed at or past EOF (e.g., a single very long line).
                // Retain the last MIN_RETAIN_BYTES to preserve recent data.
                val retainFrom = (fileLength - MIN_RETAIN_BYTES).coerceAtLeast(0L)
                if (retainFrom == 0L) return // file is smaller than minimum — nothing to do

                val retained: ByteArray = RandomAccessFile(logFile, "r").use { raf ->
                    raf.seek(retainFrom)
                    val size = (fileLength - retainFrom).toInt()
                    ByteArray(size).also { buf -> raf.readFully(buf) }
                }
                RandomAccessFile(logFile, "rw").use { raf ->
                    raf.setLength(0)
                    raf.seek(0)
                    raf.write(retained)
                }
                return
            }

            // Read only the retained portion, then overwrite the file.
            val retained: ByteArray = RandomAccessFile(logFile, "r").use { raf ->
                raf.seek(keepFrom)
                val size = (fileLength - keepFrom).toInt()
                ByteArray(size).also { buf -> raf.readFully(buf) }
            }

            RandomAccessFile(logFile, "rw").use { raf ->
                raf.setLength(0)
                raf.seek(0)
                raf.write(retained)
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

        /**
         * Minimum bytes to retain during rotation when the midpoint falls at EOF
         * (e.g., when the file contains a single very long line). Defaults to 1 MB.
         */
        private const val MIN_RETAIN_BYTES = 1L * 1024 * 1024
    }
}
