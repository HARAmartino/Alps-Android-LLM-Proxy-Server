package com.llmproxy.server

import com.llmproxy.model.ServerConfig
import com.llmproxy.logging.AccessLogEntry
import com.llmproxy.logging.AccessLogger
import com.llmproxy.logging.SystemLogger
import com.llmproxy.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.plugins.ConnectTimeoutException
import io.ktor.client.plugins.HttpRequestTimeoutException
import io.ktor.client.plugins.SocketTimeoutException
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.ApplicationCall
import io.ktor.server.application.call
import io.ktor.server.request.path
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respondText
import io.ktor.server.response.respond
import io.ktor.server.routing.get
import io.ktor.server.routing.handle
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.flush
import java.util.concurrent.atomic.AtomicReference
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

internal fun Application.installProxyRoutes(
    config: ServerConfig,
    activeConnections: MutableStateFlow<Int>,
    upstreamClient: HttpClient,
    accessLogger: AccessLogger? = null,
    systemLogger: SystemLogger? = null,
    loggerScope: CoroutineScope? = null,
    onRequestLatencyMeasured: (Long) -> Unit = {},
    onRateLimitStatusChanged: (RateLimitStatus) -> Unit = {},
) {
    // Middleware order matters: auth first, then rate-limit, then routing.
    installAuthMiddleware(config = config, systemLogger = systemLogger)
    installRateLimitMiddleware(
        config = config,
        accessLogger = accessLogger,
        onStatusChanged = onRateLimitStatusChanged,
    )

    routing {
        get("/health") {
            call.respondText("ok")
        }

        route("/{path...}") {
            handle {
                if (call.request.path() == "/health") {
                    call.respondText("ok")
                    return@handle
                }
                val pathSegments = call.parameters.getAll("path").orEmpty()
                val targetUrl = ProxyRequestMapper.buildUpstreamUrl(
                    baseUrl = config.upstreamUrl,
                    pathSegments = pathSegments,
                    queryString = call.request.queryString(),
                )
                val requestStartNs = System.nanoTime()

                val upstreamBodyChannelRef = AtomicReference<ByteReadChannel?>(null)
                activeConnections.update { it + 1 }
                try {
                    upstreamClient.prepareRequest {
                        url(targetUrl)
                        method = call.request.httpMethod
                        headers.clear()
                        headers.appendAll(
                            ProxyRequestMapper.sanitizeRequestHeaders(
                                incomingHeaders = call.request.headers,
                                apiKey = config.apiKey,
                                targetUrl = targetUrl,
                            )
                        )
                        setBody(object : OutgoingContent.ReadChannelContent() {
                            // Request body remains stream-based from client to upstream.
                            override fun readFrom() = call.receiveChannel()
                        })
                    }.execute { upstreamResponse ->
                        call.respond(object : OutgoingContent.WriteChannelContent() {
                            override val contentLength: Long? = upstreamResponse.contentLength()
                            override val contentType = upstreamResponse.contentType()
                            override val headers = ProxyRequestMapper.sanitizeResponseHeaders(upstreamResponse.headers)
                            // Forward upstream status transparently, including non-2xx responses.
                            override val status = upstreamResponse.status

                            override suspend fun writeTo(channel: ByteWriteChannel) {
                                val bodyChannel = upstreamResponse.bodyAsChannel()
                                upstreamBodyChannelRef.set(bodyChannel)
                                try {
                                    // Zero-copy relay for SSE/chunked/non-chunked bodies.
                                    bodyChannel.copyTo(channel)
                                    channel.flush()
                                } finally {
                                    // Always release upstream channel resources.
                                    bodyChannel.cancel()
                                    upstreamBodyChannelRef.getAndSet(null)
                                }
                            }
                        })
                    }
                } catch (cancellation: CancellationException) {
                    upstreamBodyChannelRef.get()?.cancel(cancellation)
                    Logger.d("Routing", "Proxy call cancelled by client")
                    throw cancellation
                } catch (timeout: HttpRequestTimeoutException) {
                    upstreamBodyChannelRef.get()?.cancel(timeout)
                    Logger.e("Routing", "Upstream request timeout", timeout)
                    call.respondIfPossible(HttpStatusCode.GatewayTimeout, "Upstream request timeout")
                } catch (timeout: SocketTimeoutException) {
                    upstreamBodyChannelRef.get()?.cancel(timeout)
                    Logger.e("Routing", "Upstream socket timeout", timeout)
                    call.respondIfPossible(HttpStatusCode.GatewayTimeout, "Upstream socket timeout")
                } catch (timeout: ConnectTimeoutException) {
                    upstreamBodyChannelRef.get()?.cancel(timeout)
                    Logger.e("Routing", "Upstream connect timeout", timeout)
                    call.respondIfPossible(HttpStatusCode.GatewayTimeout, "Upstream connect timeout")
                } catch (error: Exception) {
                    upstreamBodyChannelRef.get()?.cancel(error)
                    Logger.e("Routing", "Proxy request failed", error)
                    call.respondIfPossible(HttpStatusCode.BadGateway, "Upstream service unavailable")
                } finally {
                    val elapsedMs = ((System.nanoTime() - requestStartNs) / 1_000_000L).coerceAtLeast(0L)
                    onRequestLatencyMeasured(elapsedMs)
                    activeConnections.update { current -> (current - 1).coerceAtLeast(0) }
                    // Fire-and-forget async access log write; never blocks the proxy pipeline.
                    if (accessLogger != null && loggerScope != null) {
                        val statusCode = call.response.status()?.value ?: 0
                        val clientIp = call.request.local.remoteAddress
                        val upstreamHost = runCatching {
                            java.net.URI(config.upstreamUrl).host.orEmpty()
                        }.getOrDefault("")
                        val requestUri = call.request.local.uri
                        loggerScope.launch {
                            accessLogger.log(
                                AccessLogEntry(
                                    timestamp = java.time.Instant.now().toString(),
                                    clientIp = clientIp,
                                    requestPath = requestUri,
                                    statusCode = statusCode,
                                    latencyMs = elapsedMs,
                                    upstreamHost = upstreamHost,
                                )
                            )
                        }
                    }
                }
            }
        }
    }
}

private suspend fun ApplicationCall.respondIfPossible(status: HttpStatusCode, message: String) {
    if (response.isCommitted || !currentCoroutineContext().isActive) {
        return
    }
    respond(status, message)
}
