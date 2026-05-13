package com.llmproxy.client.tunneling

import com.llmproxy.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout
import io.ktor.client.plugins.contentnegotiation.ContentNegotiation
import io.ktor.client.request.delete
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpStatusCode
import io.ktor.http.contentType
import kotlinx.coroutines.delay
import org.json.JSONObject
import java.time.Instant
import java.util.UUID

/**
 * Ngrok REST API client for creating and closing HTTP tunnels.
 *
 * Uses the ngrok API at [NGROK_API_BASE_URL]. Requests are coroutine-safe and
 * respect [requestTimeoutMs]. Rate-limit responses (HTTP 429) are retried with
 * exponential backoff up to [MAX_RETRIES] times before a [TunnelingException] is raised.
 *
 * Lifecycle:
 *   create → [createTunnel] → server running with public URL
 *   close  → [TunnelSession.close] → DELETE /api/tunnels/{name}
 */
class NgrokRestClient(
    private val requestTimeoutMs: Long = 30_000L,
    private val testHttpClient: HttpClient? = null,
    private val delayMillis: suspend (Long) -> Unit = { delay(it) },
    private val tunnelNameGenerator: () -> String = { "alps-proxy-${UUID.randomUUID()}" },
) : TunnelingClient {

    // Dedicated Ktor CIO client scoped to ngrok API calls only.
    private val httpClient: HttpClient = testHttpClient ?: HttpClient(CIO) {
        expectSuccess = false
        install(ContentNegotiation)
        install(HttpTimeout) {
            requestTimeoutMillis = this@NgrokRestClient.requestTimeoutMs
            connectTimeoutMillis = 15_000L
            socketTimeoutMillis = this@NgrokRestClient.requestTimeoutMs
        }
    }

    /**
     * Creates a new ngrok HTTP tunnel pointing to [localPort] on localhost.
     *
     * On HTTP 401: throws [TunnelingException] with an auth-error message.
     * On HTTP 429: retries with exponential backoff up to [MAX_RETRIES] times.
     * On other non-2xx: throws [TunnelingException] with the status code.
     *
     * @return [TunnelSession] containing the assigned public URL and tunnel name.
     */
    override suspend fun createTunnel(localPort: Int, authToken: String): TunnelSession {
        val tunnelName = tunnelNameGenerator()
        val requestBody = JSONObject().apply {
            put("addr", "localhost:$localPort")
            put("proto", "http")
            put("name", tunnelName)
        }.toString()

        var attempt = 0
        while (true) {
            val response = runCatching {
                httpClient.post("$NGROK_API_BASE_URL/api/tunnels") {
                    header("Authorization", "Bearer $authToken")
                    header("Ngrok-Version", "2")
                    contentType(ContentType.Application.Json)
                    setBody(requestBody)
                }
            }.getOrElse { cause ->
                throw TunnelingException("Network error contacting ngrok API: ${cause.message}", cause)
            }

            when (response.status) {
                HttpStatusCode.Created, HttpStatusCode.OK -> {
                    // Parse the JSON response and encode authToken into the session for close()
                    val body = response.bodyAsText()
                    val session = parseTunnelSession(body)
                    return session.copy(
                        sessionToken = encodeSessionToken(session.sessionToken, authToken),
                    )
                }
                HttpStatusCode.Unauthorized, HttpStatusCode.Forbidden -> {
                    throw TunnelingException("Invalid ngrok auth token. Check Settings → Tunnel Auth Token.")
                }
                HttpStatusCode.TooManyRequests -> {
                    // Retry with exponential backoff: 2s, 4s, 8s …
                    if (attempt >= MAX_RETRIES) {
                        throw TunnelingException("ngrok rate limit exceeded after $MAX_RETRIES retries. Try again later.")
                    }
                    val backoffMs = BACKOFF_BASE_MS shl attempt
                    Logger.d(TAG, "ngrok rate limit (429), retrying in ${backoffMs}ms (attempt ${attempt + 1}/$MAX_RETRIES)")
                    delayMillis(backoffMs)
                    attempt++
                }
                else -> {
                    val body = runCatching { response.bodyAsText() }.getOrElse { "" }
                    throw TunnelingException(
                        "ngrok API error ${response.status.value}: ${body.take(MAX_ERROR_BODY_LENGTH)}"
                    )
                }
            }
        }
    }

    /**
     * Closes (destroys) this tunnel by sending DELETE /api/tunnels/{sessionToken}.
     * Errors during close are logged but not re-thrown to avoid masking server shutdown errors.
     */
    override suspend fun TunnelSession.close() {
        // Retrieve auth token from the sessionToken field which encodes "name|token"
        val (tunnelName, authToken) = decodeSessionToken(sessionToken)
        runCatching {
            val response = httpClient.delete("$NGROK_API_BASE_URL/api/tunnels/$tunnelName") {
                header("Authorization", "Bearer $authToken")
                header("Ngrok-Version", "2")
            }
            if (response.status != HttpStatusCode.NoContent && response.status != HttpStatusCode.OK) {
                Logger.d(TAG, "ngrok tunnel close returned ${response.status.value} for tunnel '$tunnelName'")
            } else {
                Logger.d(TAG, "ngrok tunnel '$tunnelName' closed successfully")
            }
        }.onFailure { error ->
            Logger.e(TAG, "Error closing ngrok tunnel '$tunnelName'", error)
        }
    }

    // ----- helpers -----

    private fun parseTunnelSession(responseBody: String): TunnelSession {
        return runCatching {
            val json = JSONObject(responseBody)
            val publicUrl = json.getString("public_url")
            // Store tunnelName|authToken so close() can call the right endpoint.
            // The name comes back in the response JSON as "name".
            val name = json.optString("name").ifBlank { "alps-proxy" }
            val expiresAt = json.optString("expires_at")
                .takeIf { it.isNotBlank() }
                ?.let { value -> runCatching { Instant.parse(value) }.getOrNull() }
            TunnelSession(
                publicUrl = publicUrl,
                sessionToken = name,
                expiresAt = expiresAt,
            )
        }.getOrElse { cause ->
            throw TunnelingException("Failed to parse ngrok API response: ${cause.message}", cause)
        }
    }

    /**
     * Encode tunnel name and auth token together so [TunnelSession.close] has all it needs.
     * The separator [SESSION_TOKEN_SEPARATOR] is unlikely to appear in either part.
     */
    internal fun encodeSessionToken(tunnelName: String, authToken: String): String =
        "$tunnelName$SESSION_TOKEN_SEPARATOR$authToken"

    private fun decodeSessionToken(sessionToken: String): Pair<String, String> {
        val idx = sessionToken.indexOf(SESSION_TOKEN_SEPARATOR)
        return if (idx < 0) {
            // Legacy / simple format: sessionToken is just the tunnel name, no auth available.
            sessionToken to ""
        } else {
            sessionToken.substring(0, idx) to sessionToken.substring(idx + SESSION_TOKEN_SEPARATOR.length)
        }
    }

    private companion object {
        private const val TAG = "NgrokRestClient"
        private const val NGROK_API_BASE_URL = "https://api.ngrok.com"
        private const val MAX_RETRIES = 4
        private const val BACKOFF_BASE_MS = 2_000L
        private const val MAX_ERROR_BODY_LENGTH = 300
        private const val SESSION_TOKEN_SEPARATOR = "\u0000"
    }
}
