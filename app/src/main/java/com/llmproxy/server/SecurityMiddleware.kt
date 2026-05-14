package com.llmproxy.server

import com.llmproxy.logging.AccessLogEntry
import com.llmproxy.logging.AccessLogger
import com.llmproxy.logging.SystemLogger
import com.llmproxy.model.ServerConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpMethod
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.plugins.cors.routing.CORS
import io.ktor.server.plugins.cors.routing.CORSConfig
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.net.InetAddress
import java.net.URI
import java.security.MessageDigest
import java.nio.charset.StandardCharsets
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.min

internal data class RateLimitStatus(
    val trackedIpCount: Int = 0,
    val blockedRequestCount: Long = 0L,
)

internal fun Application.installAuthMiddleware(
    config: ServerConfig,
    systemLogger: SystemLogger? = null,
) {
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.isCorsPreflightRequest()) {
            return@intercept
        }
        if (!config.requireBearerAuth || call.request.path() == "/health") {
            return@intercept
        }

        val providedToken = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter("Bearer ", "")
            ?.trim()
            .orEmpty()

        val expectedToken = config.bearerToken
        val isValid = constantTimeTokenMatch(providedToken, expectedToken)

        if (!isValid) {
            systemLogger?.warn(
                tag = "AuthMiddleware",
                message = "Unauthorized request rejected path=${call.request.path()} ip=${call.clientIp(config)}",
            )
            call.response.headers.append(HttpHeaders.WWWAuthenticate, "Bearer")
            call.respond(HttpStatusCode.Unauthorized, "Unauthorized")
            finish()
        }
    }
}

internal fun Application.installRateLimitMiddleware(
    config: ServerConfig,
    accessLogger: AccessLogger? = null,
    onStatusChanged: (RateLimitStatus) -> Unit = {},
) {
    val limiter = IpTokenBucketLimiter()
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.isCorsPreflightRequest()) {
            return@intercept
        }
        if (call.request.path() == "/health") {
            return@intercept
        }

        val rpm = config.maxRequestsPerMinute.coerceAtLeast(1)
        val clientIp = call.clientIp(config)
        val decision = limiter.tryConsume(clientIp, rpm)
        onStatusChanged(decision.status)

        if (!decision.allowed) {
            accessLogger?.log(
                AccessLogEntry(
                    timestamp = Instant.now().toString(),
                    clientIp = clientIp,
                    requestPath = call.request.local.uri,
                    statusCode = HttpStatusCode.TooManyRequests.value,
                    latencyMs = 0L,
                    upstreamHost = "rate_limiter",
                )
            )
            call.response.headers.append(HttpHeaders.RetryAfter, decision.retryAfterSeconds.toString())
            call.respond(HttpStatusCode.TooManyRequests, "Rate limit exceeded")
            finish()
        }
    }
}

internal fun Application.installIpWhitelistMiddleware(
    config: ServerConfig,
    systemLogger: SystemLogger? = null,
) {
    if (!config.enableIpWhitelist) {
        return
    }
    val whitelistMatchers = config.ipWhitelist
        .mapNotNull { entry -> parseIpWhitelistEntry(entry) }
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.isCorsPreflightRequest()) {
            return@intercept
        }
        if (call.request.path() == "/health") {
            return@intercept
        }
        // Empty whitelist keeps behavior open even when the toggle is enabled.
        if (whitelistMatchers.isEmpty()) {
            return@intercept
        }
        val clientIp = call.clientIp(config)
        if (whitelistMatchers.any { matcher -> matcher.matches(clientIp) }) {
            return@intercept
        }
        systemLogger?.warn(
            tag = "IpWhitelistMiddleware",
            message = "Blocked request path=${call.request.path()} ip=$clientIp",
        )
        call.respond(HttpStatusCode.Forbidden, "Forbidden")
        finish()
    }
}

internal fun Application.installCorsMiddleware(
    config: ServerConfig,
    systemLogger: SystemLogger? = null,
) {
    install(CORS) {
        allowMethod(HttpMethod.Get)
        allowMethod(HttpMethod.Post)
        allowMethod(HttpMethod.Options)
        allowHeader(HttpHeaders.ContentType)
        allowHeader(HttpHeaders.Authorization)
        exposeHeader(HttpHeaders.AccessControlAllowOrigin)
        exposeHeader(HttpHeaders.AccessControlAllowMethods)
        exposeHeader(HttpHeaders.AccessControlAllowHeaders)

        val origins = config.corsAllowedOrigins
            .map { it.trim() }
            .filter { it.isNotBlank() }
            .ifEmpty { listOf(ServerConfig.DEFAULT_CORS_ALLOWED_ORIGIN) }
        if (origins.any { it == "*" }) {
            anyHost()
        } else {
            // Each origin is expected as a full URL (scheme + host + optional port).
            origins.forEach { origin ->
                runCatching {
                    val parsed = URI(origin)
                    val host = parsed.host?.takeIf { it.isNotBlank() } ?: return@runCatching
                    val schemes = listOfNotNull(parsed.scheme?.takeIf { it.isNotBlank() })
                    if (schemes.isNotEmpty()) {
                        allowHost(host = host, schemes = schemes)
                    } else {
                        allowHost(host = host)
                    }
                }.onFailure {
                    systemLogger?.warn(
                        tag = "CorsMiddleware",
                        message = "Ignoring invalid CORS origin: $origin",
                    )
                }
            }
        }
    }
    intercept(ApplicationCallPipeline.Plugins) {
        if (call.request.isCorsPreflightRequest()) {
            systemLogger?.debug(
                tag = "CorsMiddleware",
                message = "CORS preflight path=${call.request.path()} origin=${call.request.headers[HttpHeaders.Origin].orEmpty()}",
            )
        }
    }
}

/**
 * Per-IP token bucket:
 * - Capacity = configured RPM.
 * - Refill speed = RPM / 60s.
 * - Each request consumes one token.
 */
private class IpTokenBucketLimiter {
    private val blockedCount = AtomicLong(0L)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryConsume(ip: String, requestsPerMinute: Int, currentTimeMs: Long = System.currentTimeMillis()): Decision {
        val rpm = requestsPerMinute.coerceAtLeast(1)
        val capacity = rpm.toDouble()
        val refillPerMs = capacity / 60_000.0
        val bucket = getOrCreateBucket(ip, capacity, currentTimeMs)
        val retryAfterSeconds = synchronized(bucket.lock) {
            if (currentTimeMs < bucket.lastRefillAtMs) {
                // Recover from wall-clock rollback to avoid freezing token refills.
                bucket.lastRefillAtMs = currentTimeMs
            }
            val elapsedMs = (currentTimeMs - bucket.lastRefillAtMs).coerceAtLeast(0L)
            if (elapsedMs > 0L) {
                // Refill lazily on each request so no background timer is needed.
                bucket.tokens = min(capacity, bucket.tokens + (elapsedMs * refillPerMs))
                bucket.lastRefillAtMs = currentTimeMs
            }

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
                0L
            } else {
                val missingTokens = 1.0 - bucket.tokens
                val retryAfterMs = ceil(missingTokens / refillPerMs).toLong().coerceAtLeast(1L)
                ceil(retryAfterMs / 1_000.0).toLong().coerceAtLeast(1L)
            }
        }

        if (retryAfterSeconds > 0L) {
            blockedCount.incrementAndGet()
        }
        maybeCleanup(currentTimeMs)
        val blocked = blockedCount.get()
        val status = RateLimitStatus(
            trackedIpCount = buckets.size,
            blockedRequestCount = blocked,
        )
        return Decision(allowed = retryAfterSeconds == 0L, retryAfterSeconds = retryAfterSeconds, status = status)
    }

    private fun getOrCreateBucket(ip: String, capacity: Double, currentTimeMs: Long): Bucket {
        buckets[ip]?.let { return it }
        val newBucket = Bucket(tokens = capacity, lastRefillAtMs = currentTimeMs)
        val existing = buckets.putIfAbsent(ip, newBucket)
        return existing ?: newBucket
    }

    private fun maybeCleanup(currentTimeMs: Long) {
        if (buckets.size <= MAX_TRACKED_IPS) return
        val staleIps = buckets.entries
            .asSequence()
            .filter { (_, bucket) -> currentTimeMs - bucket.lastRefillAtMs > STALE_BUCKET_MS }
            .map { (ip, _) -> ip }
            .toList()
        staleIps.forEach { ip ->
            buckets.remove(ip)
        }
        val overflow = buckets.size - MAX_TRACKED_IPS
        if (overflow > 0) {
            val evictionTargets = buckets.entries
                .asSequence()
                .sortedBy { (_, bucket) -> bucket.lastRefillAtMs }
                .take(overflow)
                .map { (ip, _) -> ip }
                .toList()
            evictionTargets.forEach { ip ->
                buckets.remove(ip)
            }
        }
    }

    private class Bucket(
        var tokens: Double,
        var lastRefillAtMs: Long,
        val lock: Any = Any(),
    )

    data class Decision(
        val allowed: Boolean,
        val retryAfterSeconds: Long,
        val status: RateLimitStatus,
    )

    private companion object {
        private const val MAX_TRACKED_IPS = 2_000
        private const val STALE_BUCKET_MS = 5 * 60_000L
    }
}

private fun io.ktor.server.application.ApplicationCall.clientIp(config: ServerConfig): String {
    val trustForwardedHeader = config.networkMode == ServerConfig.NETWORK_MODE_TUNNELING
    val forwarded = request.headers["X-Forwarded-For"]
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
        .orEmpty()
    if (trustForwardedHeader && forwarded.isNotBlank()) {
        return forwarded
    }
    return request.local.remoteAddress
}

private fun io.ktor.server.request.ApplicationRequest.isCorsPreflightRequest(): Boolean {
    return httpMethod == HttpMethod.Options &&
        !headers[HttpHeaders.Origin].isNullOrBlank() &&
        !headers[HttpHeaders.AccessControlRequestMethod].isNullOrBlank()
}

private fun parseIpWhitelistEntry(entry: String): IpMatcher? {
    val trimmed = entry.trim()
    if (trimmed.isBlank()) {
        return null
    }
    val cidrParts = trimmed.split("/", limit = 2)
    return if (cidrParts.size == 2) {
        val prefixLength = cidrParts[1].toIntOrNull() ?: return null
        val networkBytes = runCatching { InetAddress.getByName(cidrParts[0]).address }.getOrNull() ?: return null
        if (prefixLength !in 0..(networkBytes.size * 8)) {
            return null
        }
        // CIDR match compares only the first [prefixLength] bits and ignores host bits.
        CidrMatcher(networkBytes = networkBytes, prefixLength = prefixLength)
    } else {
        val ipBytes = runCatching { InetAddress.getByName(trimmed).address }.getOrNull() ?: return null
        ExactIpMatcher(ipBytes)
    }
}

private interface IpMatcher {
    fun matches(ip: String): Boolean
}

private class ExactIpMatcher(
    private val ipBytes: ByteArray,
) : IpMatcher {
    override fun matches(ip: String): Boolean {
        val candidate = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return false
        return candidate.contentEquals(ipBytes)
    }
}

private class CidrMatcher(
    private val networkBytes: ByteArray,
    private val prefixLength: Int,
) : IpMatcher {
    override fun matches(ip: String): Boolean {
        val candidate = runCatching { InetAddress.getByName(ip).address }.getOrNull() ?: return false
        if (candidate.size != networkBytes.size) {
            return false
        }
        val fullBytes = prefixLength / 8
        val remainingBits = prefixLength % 8
        for (index in 0 until fullBytes) {
            if (candidate[index] != networkBytes[index]) {
                return false
            }
        }
        if (remainingBits == 0) {
            return true
        }
        val mask = (0xFF shl (8 - remainingBits)) and 0xFF
        return (candidate[fullBytes].toInt() and mask) == (networkBytes[fullBytes].toInt() and mask)
    }
}

private fun constantTimeTokenMatch(providedToken: String, expectedToken: String): Boolean {
    val digest = MessageDigest.getInstance("SHA-256")
    val providedHash = digest.digest(providedToken.toByteArray(StandardCharsets.UTF_8))
    val expectedHash = digest.digest(expectedToken.toByteArray(StandardCharsets.UTF_8))
    val configuredMask = if (expectedToken.isNotBlank()) 1 else 0
    val matchMask = if (MessageDigest.isEqual(providedHash, expectedHash)) 1 else 0
    return (configuredMask and matchMask) == 1
}
