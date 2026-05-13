package com.llmproxy.logging

/** Entry written by [AccessLogger] as one JSONL line per request. */
data class AccessLogEntry(
    val timestamp: String,      // ISO-8601
    val clientIp: String,
    val requestPath: String,
    val statusCode: Int,
    val latencyMs: Long,
    val upstreamHost: String,
)

/** Entry written by [SystemLogger] for debug/info/warn/error events. */
data class SystemLogEntry(
    val timestamp: String,      // ISO-8601
    val level: String,          // DEBUG | INFO | WARN | ERROR
    val tag: String,
    val message: String,
    val stacktrace: String? = null,
)

/** Union type used by [LogForwarder]. */
sealed interface LogEntry {
    data class Access(val entry: AccessLogEntry) : LogEntry
    data class System(val entry: SystemLogEntry) : LogEntry
}
