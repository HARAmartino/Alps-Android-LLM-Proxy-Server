package com.llmproxy.client.tunneling

import java.time.Instant

/**
 * Represents a live ngrok tunnel.
 *
 * Lifecycle: [TunnelingClient.createTunnel] → use [publicUrl] → [TunnelingClient.close]
 *
 * @param publicUrl  The internet-accessible URL assigned by ngrok (e.g. https://abc.ngrok.io).
 * @param sessionToken  The tunnel name/ID used to close the tunnel via DELETE /api/tunnels/{name}.
 * @param expiresAt  Optional expiry from ngrok (null if the API does not return one).
 */
data class TunnelSession(
    val publicUrl: String,
    val sessionToken: String,
    val expiresAt: Instant? = null,
)
