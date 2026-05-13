package com.llmproxy.model

data class ServerConfig(
    val upstreamUrl: String = "",
    val apiKey: String = "",
    val listenPort: Int = DEFAULT_PORT,
    val bindAddress: String = DEFAULT_BIND_ADDRESS,
) {
    val isReady: Boolean = upstreamUrl.isNotBlank() && apiKey.isNotBlank()

    companion object {
        const val DEFAULT_PORT = 8443
        const val DEFAULT_BIND_ADDRESS = "0.0.0.0"
    }
}
