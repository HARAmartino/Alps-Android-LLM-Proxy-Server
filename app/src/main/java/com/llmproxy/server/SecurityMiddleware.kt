package com.llmproxy.server

import com.llmproxy.logging.AccessLogEntry
import com.llmproxy.logging.AccessLogger
import com.llmproxy.logging.SystemLogger
import com.llmproxy.model.ServerConfig
import io.ktor.http.HttpHeaders
import io.ktor.http.HttpStatusCode
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCallPipeline
import io.ktor.server.request.path
import io.ktor.server.response.respond
import java.security.MessageDigest
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
        if (!config.requireBearerAuth || call.request.path() == "/health") {
            return@intercept
        }

        val providedToken = call.request.headers[HttpHeaders.Authorization]
            ?.takeIf { it.startsWith("Bearer ", ignoreCase = true) }
            ?.substringAfter("Bearer ", "")
            ?.trim()
            .orEmpty()

        val expectedToken = config.bearerToken
        val isValid = expectedToken.isNotBlank() &&
            MessageDigest.isEqual(providedToken.toByteArray(), expectedToken.toByteArray())

        if (!isValid) {
            systemLogger?.warn(
                tag = "AuthMiddleware",
                message = "Unauthorized request rejected path=${call.request.path()} ip=${call.clientIp()}",
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
        if (call.request.path() == "/health") {
            return@intercept
        }

        val rpm = config.maxRequestsPerMinute.coerceAtLeast(1)
        val clientIp = call.clientIp()
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

/**
 * Per-IP token bucket:
 * - Capacity = configured RPM.
 * - Refill speed = RPM / 60s.
 * - Each request consumes one token.
 */
private class IpTokenBucketLimiter {
    private val blockedCount = AtomicLong(0L)
    private val buckets = ConcurrentHashMap<String, Bucket>()

    fun tryConsume(ip: String, requestsPerMinute: Int, nowMs: Long = System.currentTimeMillis()): Decision {
        val rpm = requestsPerMinute.coerceAtLeast(1)
        val capacity = rpm.toDouble()
        val refillPerMs = capacity / 60_000.0
        var retryAfterSeconds = 0L

        buckets.compute(ip) { _, existing ->
            val bucket = existing ?: Bucket(tokens = capacity, lastRefillAtMs = nowMs)
            val elapsedMs = (nowMs - bucket.lastRefillAtMs).coerceAtLeast(0L)
            if (elapsedMs > 0L) {
                // Refill lazily on each request so no background timer is needed.
                bucket.tokens = min(capacity, bucket.tokens + (elapsedMs * refillPerMs))
                bucket.lastRefillAtMs = nowMs
            }

            if (bucket.tokens >= 1.0) {
                bucket.tokens -= 1.0
            } else {
                val missingTokens = 1.0 - bucket.tokens
                retryAfterSeconds = ceil(missingTokens / refillPerMs).toLong().coerceAtLeast(1L)
                blockedCount.incrementAndGet()
            }
            bucket
        }

        maybeCleanup(nowMs)
        val blocked = blockedCount.get()
        val status = RateLimitStatus(
            trackedIpCount = buckets.size,
            blockedRequestCount = blocked,
        )
        return Decision(allowed = retryAfterSeconds == 0L, retryAfterSeconds = retryAfterSeconds, status = status)
    }

    private fun maybeCleanup(nowMs: Long) {
        if (buckets.size <= MAX_TRACKED_IPS) return
        val iterator = buckets.entries.iterator()
        while (iterator.hasNext()) {
            val entry = iterator.next()
            if (nowMs - entry.value.lastRefillAtMs > STALE_BUCKET_MS) {
                iterator.remove()
            }
        }
    }

    private data class Bucket(
        var tokens: Double,
        var lastRefillAtMs: Long,
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

private fun io.ktor.server.application.ApplicationCall.clientIp(): String {
    val forwarded = request.headers["X-Forwarded-For"]
        ?.split(",")
        ?.firstOrNull()
        ?.trim()
        .orEmpty()
    if (forwarded.isNotBlank()) {
        return forwarded
    }
    return request.local.remoteAddress
}
