package com.llmproxy.server

import com.llmproxy.logging.AccessLogger
import com.llmproxy.logging.SystemLogger
import com.llmproxy.model.ServerConfig
import io.ktor.client.HttpClient
import io.ktor.server.application.Application
import io.ktor.server.cio.CIO
import io.ktor.server.engine.ApplicationEngine
import io.ktor.server.engine.applicationEngineEnvironment
import io.ktor.server.engine.embeddedServer
import io.ktor.server.plugins.callloging.CallLogging
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow

class ProxyServerFactory(
    private val upstreamClient: HttpClient,
    private val sslContextLoader: SslContextLoader,
    private val accessLogger: AccessLogger? = null,
    private val systemLogger: SystemLogger? = null,
    private val loggerScope: CoroutineScope? = null,
) {
    fun create(
        config: ServerConfig,
        activeConnections: MutableStateFlow<Int>,
        onRequestLatencyMeasured: (Long) -> Unit = {},
        onRateLimitStatusChanged: (RateLimitStatus) -> Unit = {},
    ): ApplicationEngine {
        val sslMaterial = sslContextLoader.loadSslContext()

        return embeddedServer(
            CIO,
            environment = applicationEngineEnvironment {
                sslConnector(
                    keyStore = sslMaterial.keyStore,
                    keyAlias = com.llmproxy.util.SslCertGenerator.KEY_ALIAS,
                    keyStorePassword = { sslMaterial.keyPassword },
                    privateKeyPassword = { sslMaterial.keyPassword },
                ) {
                    host = config.bindAddress
                    port = config.listenPort
                }
                module {
                    install(CallLogging)
                    installProxyRoutes(
                        config = config,
                        activeConnections = activeConnections,
                        upstreamClient = upstreamClient,
                        accessLogger = accessLogger,
                        systemLogger = systemLogger,
                        loggerScope = loggerScope,
                        onRequestLatencyMeasured = onRequestLatencyMeasured,
                        onRateLimitStatusChanged = onRateLimitStatusChanged,
                    )
                }
            },
        )
    }
}
