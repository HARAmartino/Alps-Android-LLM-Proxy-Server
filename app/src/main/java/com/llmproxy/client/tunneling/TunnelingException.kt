package com.llmproxy.client.tunneling

/**
 * Thrown by [TunnelingClient] implementations for known failure modes.
 *
 * @param message  Human-readable description shown in the UI.
 * @param cause    Underlying exception if available.
 */
class TunnelingException(message: String, cause: Throwable? = null) : Exception(message, cause)
