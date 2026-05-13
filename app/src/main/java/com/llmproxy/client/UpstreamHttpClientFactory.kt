package com.llmproxy.client

import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO
import io.ktor.client.plugins.HttpTimeout

class UpstreamHttpClientFactory {
    fun create(): HttpClient {
        return HttpClient(CIO) {
            expectSuccess = false
            install(HttpTimeout) {
                requestTimeoutMillis = 120_000
                connectTimeoutMillis = 30_000
                socketTimeoutMillis = 120_000
            }
            engine {
                maxConnectionsCount = 128
                endpoint {
                    connectAttempts = 2
                    keepAliveTime = 5_000
                    connectTimeout = 30_000
                    requestTimeout = 120_000
                    pipelineMaxSize = 20
                }
            }
        }
    }
}
