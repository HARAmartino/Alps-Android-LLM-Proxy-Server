package com.llmproxy.model

/**
 * Represents the lifecycle phase of the ngrok tunnel:
 * Idle → Connecting → Active (or Error on failure)
 */
enum class TunnelStatus {
    /** Tunneling mode is not active or the server is stopped. */
    Idle,

    /** Waiting for ngrok to confirm the tunnel URL. */
    Connecting,

    /** Tunnel is established and the public URL is reachable. */
    Active,

    /** Tunnel creation failed or was lost; see lastError for details. */
    Error,
}
