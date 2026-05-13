package com.llmproxy.logging

// Compile redaction regexes once to avoid recreating them on every call.
private val API_KEY_REGEX = Regex("""(?i)(["']?api_key["']?\s*[:=]\s*["']?)[\w\-]+""")
private val AUTH_HEADER_REGEX = Regex("""(?i)(Authorization\s*:\s*)[\w\-. ]+""")
private val TOKEN_REGEX = Regex("""(?i)(["']?token["']?\s*[:=]\s*["']?)[\w\-]+""")
private val BEARER_REGEX = Regex("""(?i)(Bearer\s+)[\w\-.]+""")

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
    .replace(API_KEY_REGEX, "$1[REDACTED]")
    // Redact Authorization header values, e.g. "Authorization: Bearer xyz"
    .replace(AUTH_HEADER_REGEX, "$1[REDACTED]")
    // Redact token key–value pairs, e.g. "token":"abc123" or token=abc123
    .replace(TOKEN_REGEX, "$1[REDACTED]")
    // Redact bare Bearer tokens, e.g. "Bearer eyJhbGc…"
    .replace(BEARER_REGEX, "$1[REDACTED]")
