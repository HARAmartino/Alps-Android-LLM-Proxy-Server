package com.llmproxy.logging

/**
 * Extension function that replaces sensitive patterns in log content before export.
 *
 * Covered patterns:
 * - `api_key` key–value pairs  (JSON and query-string forms)
 * - `Authorization` HTTP header values
 * - `token` key–value pairs
 * - `Bearer` token literals
 *
 * These patterns are intentionally conservative to avoid false-positive
 * redaction of non-sensitive content.
 */
internal fun String.redactSensitiveData(): String = this
    // Redact JSON/query-string api_key values, e.g. "api_key":"abc123" or api_key=abc123
    .replace(Regex("""(?i)(["']?api_key["']?\s*[:=]\s*["']?)[\w\-]+"""), "$1[REDACTED]")
    // Redact Authorization header values, e.g. "Authorization: Bearer xyz"
    .replace(Regex("""(?i)(Authorization\s*:\s*)[\w\-. ]+"""), "$1[REDACTED]")
    // Redact token key–value pairs, e.g. "token":"abc123" or token=abc123
    .replace(Regex("""(?i)(["']?token["']?\s*[:=]\s*["']?)[\w\-]+"""), "$1[REDACTED]")
    // Redact bare Bearer tokens, e.g. "Bearer eyJhbGc…"
    .replace(Regex("""(?i)(Bearer\s+)[\w\-.]+"""), "$1[REDACTED]")
