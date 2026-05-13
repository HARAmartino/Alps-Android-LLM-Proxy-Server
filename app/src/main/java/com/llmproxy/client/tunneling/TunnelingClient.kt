package com.llmproxy.client.tunneling

/**
 * Abstraction for a tunneling provider.
 *
 * Lifecycle:
 *   1. Call [createTunnel] after the local server is running to obtain a [TunnelSession]
 *      containing the internet-accessible [TunnelSession.publicUrl].
 *   2. Use the public URL until the server is stopped or network is lost.
 *   3. Call [TunnelSession.close] to release the tunnel on the provider side.
 */
interface TunnelingClient {
    /**
     * Creates a tunnel that forwards internet traffic to the local server on [localPort].
     *
     * @param localPort  The local TCP port the Ktor server is bound to (e.g. 8443).
     * @param authToken  The provider auth token (e.g. ngrok auth token).
     * @return A [TunnelSession] with the assigned public URL.
     * @throws TunnelingException on auth failure, rate-limit exhaustion, or network error.
     */
    suspend fun createTunnel(localPort: Int, authToken: String): TunnelSession

    /**
     * Closes (destroys) this tunnel session on the provider side.
     * Safe to call multiple times; repeated calls after close are no-ops.
     */
    suspend fun TunnelSession.close()
}
