package com.llmproxy.server

import com.llmproxy.model.ServerConfig
import com.llmproxy.util.Logger
import io.ktor.client.HttpClient
import io.ktor.client.request.prepareRequest
import io.ktor.client.request.setBody
import io.ktor.client.request.url
import io.ktor.client.statement.bodyAsChannel
import io.ktor.http.HttpStatusCode
import io.ktor.http.content.OutgoingContent
import io.ktor.server.application.Application
import io.ktor.server.application.call
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.callloging.CallLogging
import io.ktor.server.request.httpMethod
import io.ktor.server.request.receiveChannel
import io.ktor.server.response.respond
import io.ktor.server.routing.handle
import io.ktor.server.routing.route
import io.ktor.server.routing.routing
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update

class ProxyServerFactory(
    private val upstreamClient: HttpClient,
    private val sslContextLoader: SslContextLoader,
) {
    fun create(
        config: ServerConfig,
        activeConnections: MutableStateFlow<Int>,
    ): ApplicationEngine {
        val (_, keyStore) = sslContextLoader.loadSslContext()
        val keyPassword = com.llmproxy.util.SslCertGenerator.KEYSTORE_PASSWORD.toCharArray()

        return embeddedServer(
            CIO,
            environment = applicationEngineEnvironment {
                sslConnector(
                    keyStore = keyStore,
                    keyAlias = com.llmproxy.util.SslCertGenerator.KEY_ALIAS,
                    keyStorePassword = { keyPassword },
                    privateKeyPassword = { keyPassword },
                ) {
                    host = config.bindAddress
                    port = config.listenPort
                }
                module {
                    install(CallLogging)
                    installProxyRoutes(config, activeConnections)
                }
            },
        )
    }

    private fun Application.installProxyRoutes(
        config: ServerConfig,
        activeConnections: MutableStateFlow<Int>,
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

                    activeConnections.update { it + 1 }
                    try {
                        val upstreamResponse = upstreamClient.prepareRequest {
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
                                override fun readFrom() = call.receiveChannel()
                            })
                        }.execute()

                        call.respond(object : OutgoingContent.ReadChannelContent() {
                            override val contentLength: Long? = upstreamResponse.contentLength()
                            override val contentType = upstreamResponse.contentType()
                            override val headers = ProxyRequestMapper.sanitizeResponseHeaders(upstreamResponse.headers)
                            override val status = upstreamResponse.status
                            override fun readFrom() = upstreamResponse.bodyAsChannel()
                        })
                    } catch (error: Exception) {
                        Logger.e("ProxyServerFactory", "Proxy request failed", error)
                        call.respond(HttpStatusCode.BadGateway, "Proxy request failed: ${error.message.orEmpty()}")
                    } finally {
                        activeConnections.update { current -> (current - 1).coerceAtLeast(0) }
                    }
                }
            }
        }
    }
}
