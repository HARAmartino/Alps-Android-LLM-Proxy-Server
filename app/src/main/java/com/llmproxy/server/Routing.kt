package com.llmproxy.server

import com.llmproxy.model.ServerConfig
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
import io.ktor.server.application.call
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.handle
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import io.ktor.utils.io.ByteReadChannel
import io.ktor.utils.io.ByteWriteChannel
import io.ktor.utils.io.cancel
import io.ktor.utils.io.copyTo
import io.ktor.utils.io.flush
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

internal fun Application.installProxyRoutes(
    config: ServerConfig,
    activeConnections: MutableStateFlow<Int>,
    upstreamClient: HttpClient,
) {
    routing {
        route("/{path...}") {
            handle {
                val pathSegments = call.parameters.getAll("path").orEmpty()
                val targetUrl = ProxyRequestMapper.buildUpstreamUrl(
                    baseUrl = config.upstreamUrl,
                    pathSegments = pathSegments,
                    queryString = call.request.queryString(),
                )

                var upstreamBodyChannel: ByteReadChannel? = null
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
                                upstreamBodyChannel = bodyChannel
                                try {
                                    // Zero-copy relay for SSE/chunked/non-chunked bodies.
                                    bodyChannel.copyTo(channel)
                                    channel.flush()
                                } finally {
                                    // Always release upstream channel resources.
                                    bodyChannel.cancel()
                                }
                            }
                        })
                    }
                } catch (cancellation: CancellationException) {
                    upstreamBodyChannel?.cancel(cancellation)
                    Logger.d("Routing", "Proxy call cancelled by client")
                    throw cancellation
                } catch (timeout: HttpRequestTimeoutException) {
                    upstreamBodyChannel?.cancel(timeout)
                    Logger.e("Routing", "Upstream request timeout", timeout)
                    call.respond(HttpStatusCode.GatewayTimeout, "Upstream request timeout")
                } catch (timeout: SocketTimeoutException) {
                    upstreamBodyChannel?.cancel(timeout)
                    Logger.e("Routing", "Upstream socket timeout", timeout)
                    call.respond(HttpStatusCode.GatewayTimeout, "Upstream socket timeout")
                } catch (timeout: ConnectTimeoutException) {
                    upstreamBodyChannel?.cancel(timeout)
                    Logger.e("Routing", "Upstream connect timeout", timeout)
                    call.respond(HttpStatusCode.GatewayTimeout, "Upstream connect timeout")
                } catch (error: Exception) {
                    upstreamBodyChannel?.cancel(error)
                    Logger.e("Routing", "Proxy request failed", error)
                    call.respond(HttpStatusCode.BadGateway, "Proxy request failed: ${error.message.orEmpty()}")
                } finally {
                    activeConnections.update { current -> (current - 1).coerceAtLeast(0) }
                }
            }
        }
    }
}
