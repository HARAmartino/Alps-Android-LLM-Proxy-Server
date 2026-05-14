package com.llmproxy.model

data class ServerConfig(
    val upstreamUrl: String = "",
    val apiKey: String = "",
    val listenPort: Int = DEFAULT_PORT,
    val bindAddress: String = DEFAULT_BIND_ADDRESS,
    val networkMode: String = NETWORK_MODE_LOCAL,
    val tunnelAuthToken: String = "",
    val letsEncryptDomain: String = "",
    val letsEncryptAutoRenew: Boolean = false,
    val cloudflareApiToken: String = "",
    /** Optional webhook URL for external log forwarding (encrypted at rest). */
    val webhookForwardUrl: String = "",
    val enableWakeLock: Boolean = false,
    val enableWifiLock: Boolean = false,
    val bearerToken: String = "",
    val requireBearerAuth: Boolean = false,
    val maxRequestsPerMinute: Int = DEFAULT_MAX_REQUESTS_PER_MINUTE,
) {
    val isReady: Boolean = upstreamUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_PORT = 8443
        const val DEFAULT_BIND_ADDRESS = "0.0.0.0"

        /** Serve on the local network only (existing port-forwarding behaviour). */
        const val NETWORK_MODE_LOCAL = "local"

        /** Expose via ngrok tunnel; bind server to 127.0.0.1 instead of 0.0.0.0. */
        const val NETWORK_MODE_TUNNELING = "tunneling"

        /** Loopback address used when tunneling so the server is not exposed directly. */
        const val TUNNELING_BIND_ADDRESS = "127.0.0.1"

        /** Default per-IP request budget for token-bucket rate limiting. */
        const val DEFAULT_MAX_REQUESTS_PER_MINUTE = 60
    }
}
