package com.llmproxy.logging

import kotlinx.coroutines.delay

/**
 * Interface for optional external log forwarding.
 *
 * Implementations run on background coroutines and must be non-blocking.
 * A stub [NoOpLogForwarder] is the default; a real webhook implementation
 * can be wired in later without changing call-sites.
 */
interface LogForwarder {
    suspend fun forward(entry: LogEntry)
}

/** No-op implementation used until a real endpoint is configured. */
object NoOpLogForwarder : LogForwarder {
    override suspend fun forward(entry: LogEntry) = Unit
}

/**
 * Wraps another [LogForwarder] and retries on failure with exponential back-off.
 *
 * Failures after [maxRetries] attempts are silently swallowed so that forwarding
 * errors never surface into the proxy pipeline.
 */
class RetryingLogForwarder(
    private val delegate: LogForwarder,
    private val maxRetries: Int = 3,
) : LogForwarder {
    override suspend fun forward(entry: LogEntry) {
        var lastException: Exception? = null
        repeat(maxRetries) { attempt ->
            try {
                delegate.forward(entry)
                return // success — exit early
            } catch (e: Exception) {
                lastException = e
                // Only delay between retries, not after the final failed attempt.
                if (attempt < maxRetries - 1) {
                    // Exponential back-off: 500 ms, 1000 ms, …
                    delay(500L * (attempt + 1))
                }
            }
        }
        // All retries exhausted — log locally but do not rethrow.
        android.util.Log.w(
            "RetryingLogForwarder",
            "Forward failed after $maxRetries retries: ${lastException?.message}",
        )
    }
}
